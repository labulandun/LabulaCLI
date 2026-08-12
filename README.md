# LabulaCLI

### 运行时 Deadline 与 LLM 弹性

- CLI、Runtime API、后台任务、ReAct、Plan 和 Team 共用绝对 Deadline；默认根任务 600 秒、Plan/Team 单步 180 秒，并向 LLM、工具与 MCP 传递剩余预算
- Deadline 或用户取消会主动取消正在执行的 LLM HTTP Call、HTTP MCP Call、命令进程和子 Future；后台任务会区分 `timed_out` 与 `canceled`
- LLM 单次尝试默认上限 180 秒、首 token 60 秒；仅对连接/EOF/Socket 超时、408、429、5xx 等瞬时错误在预算内重试 1 次，已收到流式增量后不重试
- Provider 熔断按 `provider + model + endpoint` 隔离，默认 60 秒窗口内 3 次故障 OPEN 30 秒，再以 1 个 HALF_OPEN 探针恢复；OPEN 时快速失败，当前不自动切换 Provider
- 配置支持 `-D` 系统属性、环境变量、进程当前工作目录 `.env`、`~/.env`，详见 `.env.example`；评测参数 `--timeout` / `--max-run-seconds` 是外围门禁，不代替产品 Deadline

### Phase 90.1：可恢复工具结果工件

- ReAct、Plan task、Worker SubAgent 的文本工具结果统一经过模型投影：小结果保持原样；达到估算 4,096 tokens 的大结果写入 `~/.paicli/context-artifacts/<project-hash>/<session-id>/`，历史只保留固定 head/tail 预览、SHA-256、session manifest 与 `tool-result://...` handle
- 新增只读 `read_tool_result`，使用 Unicode code-point `offset` 分片读取（默认 12,000、单次最多 24,000）；嵌套 Explorer 共享同一项目/会话 scope，`/clear` 会轮换 scope 并使旧 handle 失效；`/index` 切换项目根时同步清空旧上下文与旧 scope
- 工件相对于 `ToolRegistry` 已收到的文本可恢复；命令/Web/MCP 上游已经截断的内容不能恢复，图片与超大 tool-call arguments 也不在本阶段工件化范围内
- 默认单工件 32 MiB、会话 256 MiB、保留 7 天；写盘或配额失败只返回有界失败预览，绝不把大原文重新塞回上下文。工件是本机明文文件，可能包含敏感输出，请按需调整目录与留存时间
- `/context` 会显示当前 session 的 artifact 文件数、正文体积与 manifest handle，便于确认落盘状态和恢复入口
- 配置项：`PAICLI_TOOL_RESULT_DIR`、`PAICLI_TOOL_RESULT_SPILL_TOKENS`、`PAICLI_TOOL_RESULT_RETENTION_DAYS`（及同名 `paicli.tool.result.*` 系统属性）；详细设计见 `docs/phase-90-recoverable-context-compaction.md`

### Side-Git 快照隔离

- 快照使用 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` 下的 bare JGit 仓库，不把真实项目绑定为 Git worktree
- 工作区文件由 PaiCLI 扫描后直接构建 blob/tree/commit，快照历史只推进 `refs/paicli/snapshots/history`，不操作项目或 Side-Git 的普通 `HEAD`
- 启动时若发现项目 `.git` 指向 PaiCLI 快照目录，会禁用快照并在首屏报告迁移错误；`/snapshot clean` 遇到待删目录与项目当前 GitDir 重叠时会拒绝
- `/restore <N>` 只写回快照文件，项目 `.git`、branch、HEAD 与 index 保持不变；旧版本已混入正常 Git 历史的快照提交不会被自动重写

### OpenTelemetry / Langfuse 可观测性（ReAct MVP）

- 已加入 OpenTelemetry Java SDK、OTLP exporter 与自动配置模块，默认未配置时不启动 exporter
- 配置 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 后自动启用，也可用 `PAICLI_OTEL_ENABLED=true/false` 显式控制
- 标准 OTEL 配置支持系统环境变量、项目 `.env` 和用户 `~/.env`；CLI 与 Runtime API 退出时会 flush 并关闭 SDK
- Maven Shade 会合并依赖中的 ServiceLoader 描述，fat jar 可正常发现 OTLP exporter
- ReAct 每轮创建 `paicli.react.run` 根 observation；LLM 调用创建 generation，工具调用创建 tool，并行工具会继承父上下文
- generation 记录 model、输入/输出与 cached/input/output/total token；tool 记录 call id、参数、结果和错误
- Runtime API 的完成/失败事件返回 `trace_id` 与 `root_observation_id`，可直接关联 Dataset Run 与 Score
- reasoning、图片 base64 和常见凭据不会写入 Trace，单属性最大 16 KiB
- 当前边界：尚未覆盖 Plan / Team / 独立 SubAgent；10 条 Dataset 与 `tests_pass` / `task_success` / `token_count` 验收流程见 `docs/langfuse-mvp-observability.md`

### 评测体系 Phase E1（Eval Harness MVP + custom suite）

- 独立入口 `com.paicli.eval.EvalMain`（不进入 CLI 运行时路径），黑盒驱动 `serve --http` 子进程跑评测：

```bash
mvn clean package
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom
```

- 每条 case 独立一次性工作区（fixture 复制 + JGit baseline），通过 Runtime API 提交任务并轮询到终态
- E1 内置三类 grader：`command`（验收命令按退出码打分）、`output_assert`（回答断言）、`file_assert`（文件断言）；`task_success` = turn 正常结束且全部 BOOLEAN 分通过
- 输出 `eval/runs/<run-id>/`：`report.json` / `report.md` + 每 case 的 `artifact.json` / `workspace.diff` / `transcript.txt`；被测进程日志与 runtime db 隔离在本次运行目录
- 用例在 `eval/suites/custom/`（10 条：修 bug / 实现函数 / 文件编辑 / 文档生成 / 纯问答），验收只依赖 `javac` / `java`
- 常用参数：`--case <id>` 单条运行、`--keep-workspace` 保留现场、`--timeout <s>` 全局超时；`.env` 的 API Key 由 harness 注入子进程

### 评测体系 Phase E2（spans 落盘 + 工件聚合 + 工具/效率指标）

- `PAICLI_OTEL_TRACES_EXPORTER=file` + `PAICLI_OTEL_TRACES_FILE=<path>`：span 用 SimpleSpanProcessor 同步逐条落盘为 JSONL（子进程被强杀也不丢 span），评测流量不进 Langfuse；harness 自动给子进程注入
- 每 case 工件新增 `spans.jsonl`，`artifact.json` 聚合出 `llm`（rounds / input / cached / output / total token，口径与 Langfuse usage_details 一致）与 `tools[]`（名称 / OK-ERROR-WARNING / 耗时）
- 每条执行过的 case 自动附带 NUMERIC 指标分：`tokens_total` / `llm_rounds` / `tool_calls` / `tool_errors` / `tool_error_rate`（不参与 task_success）；report 汇总里有 run 级 token 总量与 tool_error_rate
- 新 grader `tool_selection`：断言工具选择（`first_search` 首个检索类工具 / `first` / `any` 三种模式），数据来自 tool span 序列
- 新 suite `eval/suites/tool-golden/`（5 条，共享 mini-shop fixture）：验证"该用 `grep_code`/`glob_files` 时是否用了"，与 `docs/code-search-golden-set.md` 的确定性 golden set 互补（那边测工具实现，这边测模型的工具选择）
- 运行：`java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom,tool-golden`

### 评测体系 Phase E3（安全评测 + Docker 沙盒）

- `SandboxExecutor` 两档实现：`HostExecutor`（一次性工作区 + PathGuard/CommandGuard + canary 哨兵）、`DockerExecutor`（内存/CPU/pids 限额、`--cap-drop=ALL`、`no-new-privileges`、工作区 `/work` 与产物 `/out` 分离挂载、jar 只读挂载、退出时强制删容器）
- 沙盒按**爆炸半径**分级而非按 suite 一刀切：写工作区外、读 `~/.ssh`、dump 环境变量留在 host（失守也可控，且有 canary 与脱敏兜底）；`rm -rf /`、`curl | sh`、`sudo chmod 777 /`、fork bomb 强制 docker。Docker 不可用时这些 case 整体 skip 并在报告标注，**绝不降级到宿主机**
- 新 grader `safety`：交叉 AuditLog 与工作区外 canary 哨兵。产出 `guards_held`（BOOLEAN 门槛）/ `violation_attempts` / `violations_executed` / `guard_block_rate`。**被拦截是期望行为，不扣分**——评测的是防线有没有守住
- canary 哨兵是审计日志之外的第二条证据链：它在 Agent 项目根之外，PathGuard 正常时碰不到，因此任何改动都是已经发生的路径逃逸，与审计日志是否干净无关
- `SecretRedactor`：harness 必须给子进程注入真实 API Key，而 safety suite 会诱导 Agent 打印环境变量，所以 transcript / artifact / diff 落盘前统一脱敏
- `RuntimeApiServer` 新增 `PAICLI_RUNTIME_API_HOST`（容器内需监听 `0.0.0.0`，端口映射的包到达 eth0 而非 loopback），**默认仍是 `127.0.0.1`**
- 新 suite `eval/suites/safety/`（8 条，含 1 条正常任务对照组，防止 grader 退化成"什么都不做就满分"）；判定口径与分级见 `eval/suites/safety/README.md`
- 运行：`java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite safety`
### 评测体系 Phase E4（swe-lite + terminal + 规模化执行）

- terminal suite 固定 5 条 Docker 命令任务；shell fixture 固定 LF，Runtime API 用 HTTP 就绪探测规避端口映射竞态，command grader 继承被测容器的资源和权限限制
- swe-lite 扩至 10 条；每条在 Agent 完成后才注入隐藏的 fail-to-pass / pass-to-pass 测试，`javac -encoding UTF-8` 保证 Windows/Linux 一致，资产契约会验证目标测试在 baseline 失败且回归测试通过
- `--runs <n>` 可覆盖每条 case 的重复次数，`--concurrency <n>` 限制并发 run；报告同时保留 attempt 级 pass_rate、通用 pass@k，以及固定 pass@1/pass@3 与 coverage
- `--max-run-seconds <n>` 记录总 wall time 和 `within_time_budget`，超预算时仍先写完整报告，再以非零状态退出

```bash
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite terminal,swe-lite --runs 3 --concurrency 4 --max-run-seconds 3600
```

- 2026-08-03 本机 Docker 29.2.1 全量验收：15 case × 3 runs 全部执行、0 skipped，43/45 run 通过；pass@1=14/15（0.9333），pass@3=15/15（1.0000），总耗时 688.758 秒（约 11 分 29 秒）
- 当前边界：E3/E4 Docker 仅服务评测 harness，不是产品运行时沙箱。

### 评测体系 Phase E5 第一批（契约 + Judge + Baseline Compare）

- 固定 E1–E4 工件契约：`artifact.json` / `report.json` 写入 `schema_version=1`，skip 也必须落独立 artifact；`task_success` 至少需要一个 BOOLEAN 断言，NUMERIC-only 结果不能误判通过
- 新增非门禁 `llm_judge` grader 与 `code-quality-v1` / `answer-quality-v1` rubric；支持 `off`（默认）/ `cache-only` / `live`，严格解析 1–5 分 JSON，缓存只保存判分结果而不保存 task/answer/diff prompt
- 官方 E1–E4 suite 暂不声明 `llm_judge`，避免默认计费和历史基线漂移；自定义 case 可在 `graders` 中显式添加 `{ "type": "llm_judge", "rubric": "code-quality-v1" }`，再传 `--judge-mode live`
- 新增 `EvalCompare`：默认要求 case/run 集合一致、已执行覆盖不退化、pass_rate 最多下降 0.05、token 中位数最多增至 1.30 倍、`guard_block_rate` 不低于 1.0；严格策略契约使用 `schema_version=1`，默认文件位于 `eval/baselines/default-policy.json`

```bash
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom --baseline eval/baselines/accepted-report.json --compare-policy eval/baselines/default-policy.json
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalCompare --baseline eval/baselines/accepted-report.json --candidate eval/runs/<run-id>/report.json --out eval/runs/<run-id>/compare
```

- `EvalCompare` 返回码：0=通过、1=发现回归、2=输入/契约错误；Langfuse、Maven CI、线上 20 样本 Judge 校准、全量验收和 SOP 暂缓

