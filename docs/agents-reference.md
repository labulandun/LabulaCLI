# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.paicli/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.paicli/memory/long_term_memory.json` | `-Dpaicli.memory.dir` |
| RAG 索引 | `~/.paicli/rag/codebase.db` | `-Dpaicli.rag.dir` |
| 审计日志 | `~/.paicli/audit/audit-YYYY-MM-DD.jsonl` | `PAICLI_AUDIT_DIR` / `-Dpaicli.audit.dir` |
| Side-Git 快照 | `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` | `PAICLI_SNAPSHOT_DIR` / `-Dpaicli.snapshot.dir` |
| 后台任务 | `~/.paicli/tasks/tasks.db` | — |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`paicli.snapshot.enabled`(true) / `paicli.snapshot.max`(50) / `paicli.snapshot.excludes`(.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `paicli.snapshot.dir`(~/.paicli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`PAICLI_LOG_DIR`(~/.paicli/logs) / `PAICLI_LOG_LEVEL`(INFO) / `PAICLI_LOG_MAX_HISTORY`(7) / `PAICLI_LOG_MAX_FILE_SIZE`(10MB) / `PAICLI_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`paicli.react.token.budget`(Integer.MAX_VALUE) / `paicli.react.stagnation.window`(3) / `paicli.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### Runtime Deadline + LLM Resilience Config

读取顺序统一为系统属性 > 环境变量 > 进程当前工作目录 `.env` > 用户 `~/.env` > 默认值；配置在进程内首次加载后保持不变，CLI `/cd` 不会重新加载这些值。

| 行为 | 系统属性 / 环境变量 | 默认值 |
|------|---------------------|--------|
| 根 Agent Deadline | `paicli.agent.deadline.seconds` / `PAICLI_AGENT_DEADLINE_SECONDS` | 600s |
| Plan/Team 单步上限 | `paicli.agent.step.deadline.seconds` / `PAICLI_AGENT_STEP_DEADLINE_SECONDS` | 180s |
| 收尾预留 | `paicli.agent.finalization.reserve.seconds` / `PAICLI_AGENT_FINALIZATION_RESERVE_SECONDS` | 5s |
| LLM 单次尝试 / 首 token | `paicli.llm.attempt.timeout.seconds` / `PAICLI_LLM_ATTEMPT_TIMEOUT_SECONDS`; `paicli.llm.first.token.timeout.seconds` / `PAICLI_LLM_FIRST_TOKEN_TIMEOUT_SECONDS` | 180s / 60s |
| HTTP connect/read/write/call | `paicli.llm.connect.timeout.seconds` / `PAICLI_LLM_CONNECT_TIMEOUT_SECONDS`; `paicli.llm.read.timeout.seconds` / `PAICLI_LLM_READ_TIMEOUT_SECONDS`; `paicli.llm.write.timeout.seconds` / `PAICLI_LLM_WRITE_TIMEOUT_SECONDS`; `paicli.llm.call.timeout.seconds` / `PAICLI_LLM_CALL_TIMEOUT_SECONDS` | 60s / 300s / 60s / 600s |
| 最大重试 / 退避 | `paicli.llm.max.retries` / `PAICLI_LLM_MAX_RETRIES`; `paicli.llm.retry.base.delay.millis` / `PAICLI_LLM_RETRY_BASE_DELAY_MILLIS`; `paicli.llm.retry.max.delay.millis` / `PAICLI_LLM_RETRY_MAX_DELAY_MILLIS` | 1 / 250ms / 2000ms |
| 熔断开关 / 阈值 | `paicli.llm.circuit.enabled` / `PAICLI_LLM_CIRCUIT_ENABLED`; `paicli.llm.circuit.failure.threshold` / `PAICLI_LLM_CIRCUIT_FAILURE_THRESHOLD` | true / 3 |
| 熔断窗口 / OPEN 冷却 / HALF_OPEN 并发探针 | `paicli.llm.circuit.window.seconds` / `PAICLI_LLM_CIRCUIT_WINDOW_SECONDS`; `paicli.llm.circuit.open.seconds` / `PAICLI_LLM_CIRCUIT_OPEN_SECONDS`; `paicli.llm.circuit.half.open.probes` / `PAICLI_LLM_CIRCUIT_HALF_OPEN_PROBES` | 60s / 30s / 1 |

根任务使用单调时钟绝对 Deadline；Plan/Team step、LLM attempt、工具/MCP 都取父级剩余预算与自身上限的较小值。SSE `readTimeout` 是两次 read 的最大静默间隔，`first-token timeout` 与 `call timeout` 分别约束首次有效增量和整次调用。Deadline/取消会主动取消 OkHttp Call、命令进程和子 Future。

LLM 仅在尚未收到 reasoning/content/tool-call 流式增量时，对连接/EOF/Socket 超时、408、429、5xx 等瞬时错误重试；退避和 `Retry-After` 必须落在剩余预算内。最大重试配置限制为 0–3，熔断阈值限制为 1–100，HALF_OPEN 探针限制为 1–16。熔断按 `provider + model + endpoint` 维护 CLOSED/OPEN/HALF_OPEN 状态，OPEN 时快速失败；当前没有自动 Provider failover。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.paicli/mcp.json`
2. 项目级：`.paicli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。
检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP（显式同名配置优先），用于 Step 3.7 Flash 的 `web_search` / `web_fetch` 优先代理。

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 三种兜底：token 超预算 / 连续 3 轮相同调用 / 50 轮硬上限
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整 reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本
- `TerminalMarkdownRenderer` 渲染 Markdown 表格时按终端列宽分配列宽，长内容在单元格内部换行；CJK 字符按显示宽度计算，避免表格行被终端自动折断后错位

### Long Context Engineering

- `ContextProfile` 按模型 `maxContextWindow` 统一派生预算和阈值，不再维护 short/balanced/long 模式分档
- GLM-5.1: 200k / DeepSeek V4: 1M / StepFun: 256k / Kimi K2.6: 256k / FreeLLMAPI: 128k
- 完整请求估算由 `RequestContextEstimator` 统一组成：message/contentParts/image、仅 provider 实际 replay 的 reasoning、tool-call id/name/arguments、tool_call_id、message framing、实际 tools wire schema 和 request envelope；cached input 仍占窗口
- `hardInputCeiling = window - responseReserve - safetyMargin`；response reserve 默认 8,192（clamp 到 window 25%），safety 默认 `max(2,048, window 1%)`（clamp 到 window 10%），soft compression trigger 为 hard ceiling 的 90%
- MCP resources 索引在 window >=32k 时启用；精确代码定位优先实时 glob/grep/read，search_code 保留为语义辅助
- prompt caching：能力声明 + cached usage 解析

### Memory System

- 两道压缩：
  1. `ContextCompressor` 压缩 shortTermMemory
  2. `ConversationHistoryCompactor` 从完整 source conversationHistory 投影出“结构化摘要 + recent tail”的不可变 model view，不修改 source
- 第二道压缩切割在 user message 边界，保留最近 3 个 user 起算的尾部；checkpoint 只增量总结新覆盖前缀，prefix digest 校验 role/content/contentParts/tool/reasoning 等请求相关字段，source append baseline/最近观察 digest 会拒绝 retained tail 的插入、删除、重排或覆盖
- ReAct、Plan task、Worker SubAgent 的业务 `chat()` 都只发送 model view；当前 system prompt 每轮直接进入 view，artifact manifest/count/latest handle 作为动态恢复索引附加
- 摘要输出限制为 16,000 chars / 约 4,096 estimated tokens；失败不提交 checkpoint，有旧 checkpoint 时保留旧摘要并携带完整当前 tail，否则发送 source 快照
- `/clear`、项目切换、模型切换及 SubAgent/Plan 新任务重置 projection state；`getConversationHistory()` 仍是 source，`getLastModelConversationView()` 用于调试最近请求
- ReAct、Plan task、Worker SubAgent 以同一完整请求估算触发投影；Planner、摘要请求和 short-term compressor 也在 provider 前执行 hard gate。任何包含完整当前 tail 的候选超限时不发送请求、不提交新 checkpoint，并返回 `CONTEXT_VIEW_BUILD_FAILED`
- `/context`、底部 `ctx` 与 request log 共用 estimated input 口径；headless Runtime/Durable Task 将 `CONTEXT_VIEW_BUILD_FAILED` 映射为 failed。估算不是 provider 官方 tokenizer，response reserve 也不等同于 provider `max_tokens`
- 长期记忆只通过 `/save` 或用户明确要求保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`

### Multi-Agent

- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试(最多 2 次)
- SubAgent 将 LLM timeout / canceled / IOException 分别转成可区分的 ERROR 消息
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- ReAct freshness preflight：用户输入命中最新/当前/今天/今年/2026/趋势/新闻/版本等时效性关键词且未显式禁止联网时，Agent 在第一轮 LLM 前执行一次内置 `web_search`，并把结果作为 `联网预检结果` 注入当前对话。
- StepSearch 优先级：当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式 `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到 StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 PaiCLI 创建的 tab

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.paicli/skills/ < 项目级 .paicli/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### TUI (v16.1 Renderer Architecture)

- 三个实现：InlineRenderer(默认) / LanternaRenderer / PlainRenderer
- 环境变量：`PAICLI_RENDERER=inline|lanterna|plain`
- `PAICLI_TUI=true`(旧) → lanterna + deprecation 提示
- `PAICLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd。`ctx` 只表示当前仍会带入下一轮请求的上下文估算，`in/out/cache` 表示最近任务调用统计。
- `/clear` 清空当前 ReAct source conversationHistory、history projection state、shortTermMemory 和待注入 SkillContextBuffer，轮换 artifact session，并重建不含上一轮检索记忆的 system prompt；长期记忆条目保留，后续只会按新查询重新检索注入。
- 普通任务和斜杠命令提交后都会以 `>` 暗色整行块回写原始输入，避免 JLine accept 后清掉编辑行导致结果区看不到刚执行的命令
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `PAICLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 `~/.paicli/snapshots/` 维护 bare 仓库（JGit，不依赖系统 git），不配置项目 worktree
- PaiCLI 扫描工作区并通过 `ObjectInserter` / `DirCache` / `CommitBuilder` 构建快照，只推进 `refs/paicli/snapshots/history`，不操作普通 `HEAD`
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照
- 启动检测到项目 `.git` 指向快照根时会禁用快照并报告迁移错误；`/snapshot clean` 会拒绝删除与项目当前 GitDir 重叠的目录，并等待异步写入结束
- 旧版 Side-Git 的快照 HEAD 会迁移为独立快照引用但不再推进 HEAD；已经混入项目/远端历史的提交不会自动重写

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → runtime_context → project_context → skills → context_mgmt → handoff
- runtime_context 每轮注入当前日期和系统时区，供最新信息、相对日期和联网决策使用
- 覆盖优先级：jar 内置 < 用户级 ~/.paicli/prompts/ < 项目级 .paicli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Async Tasks + Runtime API (Phase 20)

- DurableTaskManager(SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Durable Task 终态区分 `completed` / `failed` / `timed_out` / `canceled`；取消与 Deadline 会先触发底层资源取消，再持久化终态
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events
- Runtime turn 使用同一产品 Deadline，失败事件的 `status` 区分 `timeout` / `canceled` / `failed`

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### PlanExecuteAgent.java
规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java
Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java
可配置角色子代理 / 独立 source history + projection checkpoint / Worker 用工具、Planner/Reviewer 不用；`clearHistory()` 只清自身 history/state，不轮换共享 artifact store

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java
DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java
内置工具 + MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult。Phase 90.1 在顶层 `executeTracedInvocation` 完成原始工具执行后统一生成模型投影：超过阈值的文本落为 UTF-8 工件，`result()` 只带预览、digest、manifest 和 opaque handle；`displayPreview` / `resultMetadata` 服务 UI 与 partial 判断，图片 parts 保持独立。`read_tool_result` 严格分页且 spill-exempt，`readOnlyExploreView()` 共享主 session store。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查，`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。

工具工件默认目录为 `~/.paicli/context-artifacts/<project-hash>/<session-id>/`；单工件 32 MiB、session 256 MiB、保留 7 天（成功读取会刷新 session 访问时间，启动时清理超过留存期且未访问的旧目录）。配置读取遵循 system property > environment > 项目 `.env` > 用户 `~/.env` > 默认值：`paicli.tool.result.dir` / `.spill.tokens` / `.retention.days`。工件正文不自动脱敏且是本机明文；producer 在 ToolRegistry 之前已经截断的内容不属于可恢复承诺。

Phase 90.2 的 `ToolResultArtifactIndex` 从 store 内存 manifest 取得稳定 handle、当前 count 和最近 5 个工件元数据；同 session 新工件只刷新 model view 中的动态索引，不使 summary checkpoint 失效。`/clear` 或项目 rebind 改变 manifest handle，会与 projection state 一起失效。

Phase 90.3 的 `LlmRequestWireFormat` 是 transport 与 estimator 的 tools schema/request envelope 共同来源；DeepSeek/Kimi 的 reasoning replay 能力和 Step `reasoning_format` / `reasoning_effort` request fields 由 `LlmClient` 能力声明统一透传。配置读取遵循既有优先级：`paicli.context.response.reserve.tokens` / `PAICLI_CONTEXT_RESPONSE_RESERVE_TOKENS`、`paicli.context.safety.margin.tokens` / `PAICLI_CONTEXT_SAFETY_MARGIN_TOKENS`。

`/index <新路径>` 改变项目根时会同步清空旧 conversation history/short-term memory 并切换 artifact session，避免旧项目句柄进入新 scope 后反复读取失败。

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### TUI Package
TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- `LlmClient.replaysReasoningContent()` 是公开能力声明：DeepSeek/Kimi 为 true，Tracing wrapper 透传；请求构造和 summary checkpoint 失效判断共用该值
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...` 写入配置后 `/model freellmapi` 切换

---

## .env.example Reference

```bash
GLM_API_KEY=your_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
# FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
# FREELLMAPI_MODEL=auto
# FREELLMAPI_BASE_URL=http://localhost:5173/v1
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# PAICLI_LOG_LEVEL=INFO
# PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
# PAICLI_LOG_MAX_HISTORY=7
# PAICLI_LOG_MAX_FILE_SIZE=10MB
# PAICLI_LOG_TOTAL_SIZE_CAP=100MB
# PAICLI_SNAPSHOT_ENABLED=true
# PAICLI_SNAPSHOT_MAX=50
# PAICLI_SNAPSHOT_EXCLUDES=.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# PAICLI_SNAPSHOT_DIR=/Users/yourname/.paicli/snapshots
# PAICLI_TUI=true
# NO_TUI=true
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
