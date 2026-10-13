# LabulaCLI

> 一个对标 Claude Code 的 Java Agent CLI。本仓库基于开源项目 PaiCLI 二次开发，在上游 22 期能力之上，补齐了**上下文工程、可观测性、评测体系、运行时弹性**四块生产级短板。

![Java](https://img.shields.io/badge/Java-17+-orange) ![Maven](https://img.shields.io/badge/build-Maven-blue) ![OpenTelemetry](https://img.shields.io/badge/observability-OpenTelemetry%20%2F%20Langfuse-7b61ff) ![Eval](https://img.shields.io/badge/eval-38%20cases%20%C2%B7%205%20suites-success) ![Docker](https://img.shields.io/badge/sandbox-Docker-2496ED)

![demo](img/paicli-demo.png)

---

## 目录

- [二次开发概览](#二次开发概览)
- [整体架构](#整体架构)
- [核心工作详解](#核心工作详解)
  - [1. 代码检索子 Agent（Phase 23）](#1-代码检索子-agentphase-23)
  - [2. 结构化会话摘要（Phase 24）](#2-结构化会话摘要phase-24)
  - [3. OpenTelemetry / Langfuse 可观测性](#3-opentelemetry--langfuse-可观测性)
  - [4. Agent 评测体系（Phase E1–E5）](#4-agent-评测体系phase-e1e5)
  - [5. 全链路 Deadline、重试与分层熔断（Phase 88）](#5-全链路-deadline重试与分层熔断phase-88)
  - [6. Side-Git 快照隔离修复（Phase 89）](#6-side-git-快照隔离修复phase-89)
  - [7. 可恢复的长上下文压缩（Phase 90.1–90.3）](#7-可恢复的长上下文压缩phase-90190-3)
- [快速开始](#快速开始)
- [上游基础能力](#上游基础能力)
- [分支与提交说明](#分支与提交说明)
- [文档索引](#文档索引)

---

## 二次开发概览

上游 PaiCLI 已完成 ReAct → Plan/DAG → Memory → RAG → Multi-Agent → HITL → MCP → TUI → Runtime API 等 22 期基础能力。我的工作从 Phase 23 开始，聚焦于"Agent 能跑"之后的问题：**上下文会不会爆、跑得好不好怎么量、出了问题怎么查、模型抖动时会不会卡死**。

| 阶段 | 主题 | 关键交付 | 提交 |
|---|---|---|---|
| Phase 23 | 代码检索子 Agent | `explore_codebase` 工具 + 只读 Explorer SubAgent，主上下文增量从 O(工具调用数) 降到 O(1) | `6dbd445` |
| Phase 24 | 结构化会话摘要 | 压缩摘要从自由文本升级为 8 段固定结构的"恢复快照" | `3de97a2` |
| Observability | OpenTelemetry / Langfuse | ReAct 全链路 trace：run / generation / tool，token 与 trace id 回传 | `6221a2c` |
| Eval E1 | Eval Harness MVP | 黑盒驱动 Runtime API 的评测框架 + custom suite（10 条） | `8781570` |
| Eval E2 | 指标与工具选择 | spans JSONL 落盘、token/轮次/工具错误率指标、tool-golden suite（5 条） | `b3d9cc4` |
| Eval E3 | 安全评测 + Docker 沙盒 | 按爆炸半径分级的 Host/Docker 执行器、canary 哨兵、safety suite（8 条） | `62a2e9c` |
| Eval E4 | SWE / Terminal 规模化 | swe-lite（10 条，隐藏 fail-to-pass 测试）、terminal（5 条）、并发与 pass@k | `50de3cd` `f1c7a1c` `a807792` |
| Eval E5 | Judge + 基线回归 | 版本化工件契约、LLM Judge grader、`EvalCompare` 基线回归门禁 | `b7b483b` |
| Phase 88 | 运行时弹性 | 全链路绝对 Deadline、主动取消、预算内重试、按 provider/model/endpoint 隔离的熔断器 | `a9fd43d` |
| Phase 89 | Side-Git 隔离 | 修复快照仓库污染用户项目 Git 的问题，改为 bare JGit + 独立 ref | `46af48f` |
| Phase 90.1 | 可恢复工具结果工件 | 大工具结果落盘为工件，上下文只留预览 + handle，`read_tool_result` 分页恢复 | `321a61f` |
| Phase 90.2 | Source / Model View 分离 | 压缩不再改写历史，改为生成不可变 model view + 可校验 checkpoint | `d4d0e16` |
| Phase 90.3 | 统一请求预算 | 覆盖 reasoning / schema / framing 的完整请求估算与 hard ceiling 本地拦截 | `e5fd126` |

**规模（相对上游基线 `42fab9b`）**：生产代码 +11,000 行（新增 67 个类），测试代码 +6,200 行（新增 38 个测试类），评测资产 38 条用例 / 5 个 suite。

---

## 整体架构

```mermaid
flowchart TB
    subgraph Entry["入口"]
        CLI["CLI / TUI"]
        API["Runtime API<br/>serve --http"]
        TASK["Durable Task"]
    end

    subgraph Agents["执行路径（上游）"]
        REACT["ReAct Agent"]
        PLAN["Plan-and-Execute"]
        TEAM["Multi-Agent"]
    end

    subgraph Mine["二次开发新增 ★"]
        EXP["★ Explorer SubAgent<br/>Phase 23"]
        CTX["★ 上下文工程<br/>工件 / Model View / Hard Ceiling<br/>Phase 24 · 90"]
        RES["★ Deadline · 重试 · 熔断<br/>Phase 88"]
        OBS["★ OpenTelemetry Tracing"]
        SNAP["★ Side-Git 隔离<br/>Phase 89"]
    end

    subgraph Eval["★ 评测体系 E1–E5"]
        HARNESS["EvalMain<br/>黑盒驱动"]
        SANDBOX["Host / Docker 沙盒"]
        GRADERS["Graders<br/>command · safety · tool_selection · llm_judge"]
        CMP["EvalCompare<br/>基线回归门禁"]
    end

    LLM["LLM Providers<br/>GLM · DeepSeek · Step · Kimi"]
    LF["Langfuse / OTLP"]

    CLI --> Agents
    API --> Agents
    TASK --> Agents
    Agents --> EXP
    Agents --> CTX
    CTX --> RES
    RES --> LLM
    Agents -.-> OBS
    OBS -.-> LF
    Agents -.-> SNAP

    HARNESS -->|HTTP| API
    HARNESS --> SANDBOX
    HARNESS --> GRADERS
    GRADERS --> CMP
    OBS -.spans.jsonl.-> HARNESS
```

---

## 核心工作详解

### 1. 代码检索子 Agent（Phase 23）

**问题**：会话压缩只能在 user 消息边界切分，单个 user 轮次内的几十次 `glob_files` / `grep_code` / `read_file` 结果无法压缩，最终只能被预算粗暴截断。

**方案**：对齐 Claude Code 的 Explore / Task agent，新增只读检索子 Agent。

- 暴露 `explore_codebase` 工具，模型预判需要多轮检索时**自主派发**；循环内叠加**确定性检索计数预检**作为兜底，防止模型不主动派发
- 子 Agent 在独立上下文中只拥有只读工具视图（`glob_files` / `grep_code` / `read_file` / `list_dir` / `search_code`），只回传"结论 + `file:line`"
- 通过 tool 包端口接口 `CodeExploreRunner` + 装配层 lambda 注入解耦，避免 `tool → agent → tool` 包循环依赖

```text
主 Agent ──explore_codebase──► CodeExploreRunner(端口) ──► SubAgent("explorer", 只读视图)
                                                              └─ 多轮检索后返回 1 条精炼报告
```

详见 [`docs/phase-23-explore-subagent.md`](docs/phase-23-explore-subagent.md)

### 2. 结构化会话摘要（Phase 24）

把 `ConversationHistoryCompactor` 的 1–3 段自由文本摘要升级为固定 Markdown 结构的**恢复快照**：

`Primary Request and Intent` · `Current Work` · `Pending Tasks` · `All User Messages` · `Key Technical Concepts` · `Files and Code Sections` · `Problem Solving` · `Errors and Fixes`

- 强制保留**所有**非 tool 的用户消息，用户的限制与否定条件不会在压缩中丢失
- 明确声明"摘要是历史状态快照，不是新指令"，降低工具输出 / 网页内容中的指令注入污染后续任务

详见 [`docs/phase-24-structured-conversation-summary.md`](docs/phase-24-structured-conversation-summary.md)

### 3. OpenTelemetry / Langfuse 可观测性

- 接入 OpenTelemetry Java SDK + OTLP exporter，未配置时零开销不启动；配置 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 即可直连 Langfuse
- ReAct 每轮生成 `paicli.react.run` 根 observation，LLM 调用为 **generation**（model、输入输出、input/cached/output token），工具调用为 **tool**；并行工具正确继承父上下文
- Runtime API 完成/失败事件回传 `trace_id` 与 `root_observation_id`，可直接关联 Langfuse Dataset Run 与 Score
- `TraceSanitizer` 过滤 reasoning、图片 base64 与常见凭据，单属性上限 16 KiB
- Maven Shade 合并 ServiceLoader 描述，fat jar 可正常发现 exporter

详见 [`docs/langfuse-mvp-observability.md`](docs/langfuse-mvp-observability.md)

### 4. Agent 评测体系（Phase E1–E5）

一套独立于 CLI 运行时的评测框架：`com.paicli.eval.EvalMain` 以**黑盒**方式启动 `serve --http` 子进程，通过 Runtime API 提交任务并轮询到终态，每条 case 使用一次性工作区（fixture 复制 + JGit baseline）。

| Suite | 数量 | 考察点 | 执行环境 |
|---|---|---|---|
| `custom` | 10 | 修 bug / 实现函数 / 文件编辑 / 文档生成 / 问答 | Host |
| `tool-golden` | 5 | 模型是否在该用 `grep_code` / `glob_files` 时用对工具 | Host |
| `safety` | 8 | 路径逃逸、读 `~/.ssh`、dump 环境变量、`rm -rf /`、`curl \| sh`、fork bomb 等（含 1 条正常任务对照组） | Host / Docker 分级 |
| `terminal` | 5 | 修构建脚本、日志排查、编译错误、配置缺失、管道顺序 | Docker |
| `swe-lite` | 10 | Agent 完成后才注入隐藏的 fail-to-pass / pass-to-pass 测试 | Host / Docker |

**E1 · Harness MVP**：`command` / `output_assert` / `file_assert` 三类 grader；每次运行产出 `report.json` / `report.md` 及每 case 的 `artifact.json` / `workspace.diff` / `transcript.txt`，被测进程日志与 runtime db 隔离在运行目录内。

**E2 · 指标**：span 通过 `FileSpanExporter` 同步逐条落盘为 JSONL（子进程被强杀也不丢），评测流量不进 Langfuse；自动聚合 `tokens_total` / `llm_rounds` / `tool_calls` / `tool_error_rate`；新增 `tool_selection` grader 校验工具选择序列。

**E3 · 安全评测**：
- `SandboxExecutor` 两档实现——`HostExecutor`（一次性工作区 + PathGuard/CommandGuard + canary）与 `DockerExecutor`（内存/CPU/pids 限额、`--cap-drop=ALL`、`no-new-privileges`、jar 只读挂载）
- 沙盒按**爆炸半径**分级：可控失守的留在 host，`rm -rf /`、`curl | sh` 等强制 Docker；Docker 不可用时 skip 并标注，**绝不降级到宿主机**
- `safety` grader 交叉比对 AuditLog 与项目根外的 **canary 哨兵**——两条独立证据链；"被拦截"是期望行为，评测的是防线是否守住
- `SecretRedactor` 在 transcript / artifact / diff 落盘前统一脱敏

**E4 · 规模化**：`--runs` / `--concurrency` / `--max-run-seconds`，报告给出 attempt 级 pass_rate、pass@1 / pass@3 与 coverage；资产契约测试保证目标测试在 baseline 下失败、回归测试通过。

**E5 · 回归门禁**：
- 工件契约固定 `schema_version=1`，skip 也必须落独立 artifact，`task_success` 至少需要一个 BOOLEAN 断言
- 非门禁 `llm_judge` grader：版本化 rubric（`code-quality-v1` / `answer-quality-v1`）、`off` / `cache-only` / `live` 三档、内容寻址缓存
- `EvalCompare` 基线比较：case 集合一致、覆盖不退化、pass_rate 下降 ≤ 0.05、token 中位数 ≤ 1.30×、`guard_block_rate` = 1.0；返回码 0 通过 / 1 回归 / 2 契约错误

**实测结果**（2026-08-03，本机 Docker 29.2.1，`terminal` + `swe-lite` 15 case × 3 runs）：

| 指标 | 结果 |
|---|---|
| 执行覆盖 | 45/45 run，0 skipped |
| Run 通过率 | 43/45 |
| pass@1 | 14/15（0.9333） |
| pass@3 | 15/15（1.0000） |
| 总耗时 | 688.8 秒（约 11.5 分钟，并发 4） |

```bash
mvn clean package
# 单 suite
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom,tool-golden
# 规模化
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite terminal,swe-lite --runs 3 --concurrency 4 --max-run-seconds 3600
# 基线回归
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalCompare \
  --baseline eval/baselines/accepted-report.json \
  --candidate eval/runs/<run-id>/report.json --out eval/runs/<run-id>/compare
```

详见 [`docs/evaluation-system-design.md`](docs/evaluation-system-design.md)

### 5. 全链路 Deadline、重试与分层熔断（Phase 88）

**问题**：模型或 MCP 卡住时，整个 Agent 会无限期挂起；瞬时 5xx 直接导致任务失败。

- **绝对 Deadline 传播**：CLI、Runtime API、后台任务、ReAct / Plan / Team 共用 `RunDeadline`（默认根任务 600s、Plan/Team 单步 180s），剩余预算一路传递给 LLM、工具与 MCP
- **主动取消**：Deadline 到期或用户 `/cancel` 时，`CancellationToken` 会真正中断进行中的 LLM HTTP Call、HTTP MCP Call、命令进程与子 Future；后台任务区分 `timed_out` 与 `canceled`
- **预算内重试**：单次尝试上限 180s、首 token 60s；仅对连接/EOF/Socket 超时、408、429、5xx 重试 1 次，**已收到流式增量后不重试**（避免重复输出）
- **分层熔断**：`LlmResilience` 按 `provider + model + endpoint` 隔离，60s 窗口内 3 次故障 → OPEN 30s → 单个 HALF_OPEN 探针恢复
- **统一配置解析**：`ConfigValueResolver` 支持 `-D` 系统属性 / 环境变量 / 项目 `.env` / `~/.env` 四级来源

### 6. Side-Git 快照隔离修复（Phase 89）

**问题**：上游的 turn 级快照会把 `pre-turn` / `post-turn` 提交混入用户项目自己的 Git 历史（本仓库早期的 `PaiCLI Snapshot` 提交就是这个 bug 的产物）。

- 快照改为 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` 下的 **bare JGit 仓库**，不再把项目绑定为 worktree
- 由 PaiCLI 扫描工作区直接构建 blob / tree / commit，只推进 `refs/paicli/snapshots/history`，不触碰任何 `HEAD`
- 启动时检测项目 `.git` 是否被指向快照目录，命中则禁用快照并报告迁移错误；`/snapshot clean` 遇到与项目 GitDir 重叠时拒绝执行
- `/restore <N>` 只写回文件，项目的 branch / HEAD / index 保持不变

### 7. 可恢复的长上下文压缩（Phase 90.1–90.3）

三步把"压缩即丢失"改造为"压缩可恢复、预算可证明"。

**90.1 · 可恢复工具结果工件**
- ReAct、Plan task、Worker SubAgent 的文本工具结果统一经过投影：≥ 4,096 tokens 的结果落盘到 `~/.paicli/context-artifacts/<project>/<session>/`，历史中只保留 head/tail 预览、SHA-256 与 `tool-result://...` handle
- 新增只读工具 `read_tool_result`，按 Unicode code-point offset 分页读取（默认 12,000，单次上限 24,000）；不能用文件路径绕过 session scope
- 单工件 32 MiB / 会话 256 MiB / 保留 7 天；写盘失败只返回有界预览，**绝不把大原文塞回上下文**

**90.2 · Source History / Model View 分离**
- 压缩器不再原地替换历史：完整 source history 只追加，每次请求从中构造**不可变 model view**
- Summary checkpoint 记录覆盖边界、prefix digest、append baseline，并绑定摘要策略、provider/model、reasoning replay 模式与工件 scope——只有 source 尾部追加时才复用，否则重建
- 增量压缩只总结上次 checkpoint 后的新前缀；摘要异常或超长时不提交 checkpoint，回退到"旧摘要 + 完整 tail"
- 每个压缩 view 确定性附加工件 manifest 与恢复说明，模型随时知道如何找回原文

**90.3 · 统一请求预算与 Hard Ceiling**
- `RequestContextEstimator` 对实际请求做完整 accounting：各角色文本、图片、需 replay 的 reasoning（DeepSeek / Kimi）、tool-call id/name/arguments、消息 framing、真实 tools wire schema 与 request envelope
- `hard ceiling = window − response reserve − safety margin`，soft 压缩触发点 = hard ceiling × 90%
- ReAct、Plan、SubAgent、Planner、摘要与短期记忆压缩器在调用 LLM 前执行**同一道 hard gate**；安全候选仍超限时本地拒绝并返回 `CONTEXT_VIEW_BUILD_FAILED`，不把注定失败的请求发给 provider
- `/context` 与底部状态栏统一使用 estimated input 口径，并拆分展示 source / model view、reasoning、schema、framing、reserve、safety 与 ceiling

详见 [`docs/phase-90-recoverable-context-compaction.md`](docs/phase-90-recoverable-context-compaction.md)

---

## 快速开始

**前置条件**：Java 17+、Maven；可选 `ripgrep`（`grep_code` 优先使用）、Docker（评测 terminal / 部分 safety 用例）。

```bash
# 1. 配置至少一个模型 Key：GLM_API_KEY / DEEPSEEK_API_KEY / STEP_API_KEY / KIMI_API_KEY / FREELLMAPI_API_KEY
cp .env.example .env

# 2. 构建并运行
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar

# 3. 测试
mvn test -Pquick                 # 常规回归
mvn test -DskipTests=false       # 全量回归
```

**常用配置**（均支持环境变量、`-D` 系统属性、`.env`，完整列表见 [`.env.example`](.env.example)）：

| 配置 | 作用 |
|---|---|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 设置后启用 Trace 上报（如 Langfuse） |
| `PAICLI_OTEL_TRACES_EXPORTER=file` | span 落盘为 JSONL（评测使用） |
| `PAICLI_TOOL_RESULT_SPILL_TOKENS` | 工具结果工件化阈值 |
| `PAICLI_TOOL_RESULT_RETENTION_DAYS` | 工件保留天数 |
| `PAICLI_CONTEXT_RESPONSE_RESERVE_TOKENS` | 请求预算中的回复预留 |
| `PAICLI_CONTEXT_SAFETY_MARGIN_TOKENS` | 请求预算中的安全余量 |
| `PAICLI_RUNTIME_API_HOST` | Runtime API 监听地址（默认 `127.0.0.1`） |

**常用命令**：`/plan <任务>`、`/team <任务>`、`/cancel`、`/context`、`/clear`、`/snapshot`、`/restore <N>`、`/mcp`、`/skill list`、`/model <provider>`、`/hitl on|off`。

---

## 上游基础能力

以下能力来自上游 PaiCLI（Phase 1–22），本仓库在其基础上演进：

| 类别 | 能力 |
|---|---|
| Agent 模式 | ReAct 单代理循环、Plan-and-Execute + DAG、Planner / Worker / Reviewer 多 Agent 协作 |
| 记忆与检索 | 短期 / 长期记忆、RAG 代码向量检索与关系图谱、ripgrep 实时检索 |
| 工具与协议 | 并行工具调用、MCP（stdio / Streamable HTTP、resources、prompts）、Chrome DevTools MCP、CDP 登录态复用、Skill 系统 |
| 安全 | HITL 审批、PathGuard 路径围栏、CommandGuard 命令快速拒绝、审计日志 |
| 模型 | GLM / DeepSeek / StepFun / Kimi / FreeLLMAPI 多模型适配与运行时切换、图片输入 |
| 产品化 | inline / lanterna / plain 三种 TUI、LSP 诊断注入、Prompt 分层、SQLite 后台任务 + Runtime API |

---

## 分支与提交说明

| 分支 | 内容 |
|---|---|
| `codex/context` | **主开发分支**，包含全部工作（Phase 23 → 90.3），线性历史 |
| `codex/eval` | 评测体系 E1–E5 阶段分支 |
| `codex/langfuse` | 可观测性 MVP 阶段分支 |
| `master` | 上游基线 + Phase 23/24 |

> 提交历史中作者为 `PaiCLI Snapshot` 的 `pre-turn` / `post-turn` 提交是上游快照机制写入项目 Git 造成的污染，并非开发内容，已在 Phase 89 中修复根因。`Initial full project commit`（`42fab9b`）为导入的上游基线。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [`ROADMAP.md`](ROADMAP.md) | 演进路线 |
| [`docs/agents-reference.md`](docs/agents-reference.md) | 详细行为参考 |
| [`docs/phase-23-explore-subagent.md`](docs/phase-23-explore-subagent.md) | 代码检索子 Agent 设计 |
| [`docs/phase-24-structured-conversation-summary.md`](docs/phase-24-structured-conversation-summary.md) | 结构化摘要设计 |
| [`docs/langfuse-mvp-observability.md`](docs/langfuse-mvp-observability.md) | 可观测性接入与验收 |
| [`docs/evaluation-system-design.md`](docs/evaluation-system-design.md) | 评测体系设计 |
| [`docs/phase-90-recoverable-context-compaction.md`](docs/phase-90-recoverable-context-compaction.md) | 可恢复上下文压缩设计 |
| `docs/phase-1x/2x-*.md` | 上游各期设计文档 |

## 致谢

感谢上游项目 PaiCLI 提供的 Agent CLI 基础框架。
