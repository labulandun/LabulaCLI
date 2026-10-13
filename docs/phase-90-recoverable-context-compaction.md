# Phase 90：可恢复长上下文管理与非破坏性压缩设计

> 状态：Phase 90.1–90.3 已于 2026-08-12 实现；定向回归与 Maven package 通过。
> 本期定位：在不重做 Memory、Runtime 会话持久化和 provider tokenizer 的前提下，对现有长上下文链路做一个可独立验收的小版本改善。
> 核心交付：大工具结果句柄化、非破坏性 LLM 请求视图，以及包含实际 tools schema/reasoning/framing/reserve/safety 的统一请求预算与 provider 前 hard ceiling。
> 现有基础：Phase 12 的长上下文能力声明与 `ContextProfile`、Phase 24 的结构化 `ConversationHistoryCompactor` 摘要、三条路径共享的 `ToolRegistry.executeTools()`。

---

## 0. 与当前实现的关系

本设计不推翻现有两道 Memory 压缩，也不把 PaiCLI 改造成完整的 event-sourcing 会话系统。它修补的是当前真正发给 LLM 的消息链路。

当前有三套容易混淆的机制：

| 机制 | 当前行为 | 能否恢复被省略内容 | 本期处理 |
|------|----------|--------------------|----------|
| 工具自己的分页/截断 | 部分工具有限制，部分工具仍可返回无界文本 | 通常不能；内容在工具返回前已经丢弃 | 保留已有资源保护，并由通用 spill 覆盖未限制的返回边界 |
| `ContextCompressor` / `shortTermMemory` | 工具结果先截到 500 字符，超预算后摘要短期记忆 | 不能作为原始对话恢复源 | 不改，继续负责 Memory 检索与短期摘要 |
| `ConversationHistoryCompactor` | 完整 `estimatedInput` 达到 soft trigger 后，从 source history 投影“摘要 + 最近消息” | source 不被摘要替换；工具正文另由 handle 恢复 | Phase 90.2 已改为不可变模型视图，Phase 90.3 已把触发基准统一为完整请求预算 |

现有 `explore_codebase` 已证明“把多轮工具噪声隔离在独立上下文，只回传精炼结论”是有效方向，但它只覆盖代码探索，且精炼结果仍是普通字符串，没有通用句柄和恢复能力。Phase 90 将可恢复的大结果处理下沉到统一工具结果边界，覆盖内置工具、MCP 工具和三条 Agent 路径。

### 0.1 设计基线代码事实（Phase 90.1 实施前）

| 事实 | 当前入口/位置 | 直接影响 |
|------|---------------|----------|
| ReAct 把完整工具文本加入历史 | `Agent.run()` 中 `Message.tool(id, toolResult.result())` | 单次大结果会永久占据后续每轮输入 |
| Plan task 同时写 `messages` 与 `allResults` | `PlanExecuteAgent.executeTask()` | 大结果在任务内可能被复制两次 |
| Worker SubAgent 同样回灌完整结果 | `SubAgent.execute()` | Team 生命周期虽短，单个 step 内仍可能爆窗 |
| 三条路径统一执行工具 | `ToolRegistry.executeTools()` → `executeTracedInvocation()` | 适合放通用落盘与投影逻辑 |
| 压缩器原地替换历史 | `ConversationHistoryCompactor.compactIfNeeded()` 的 `clear()` + `addAll()` | 调试、后续轮次和再次压缩都拿不到旧原文 |
| 压缩判定只估消息 | `TokenBudget.estimateMessagesTokens(history)` | 漏掉工具 schema、部分 reasoning 和回复预留 |
| `/context` 另算 schema | `Agent.getContextStatus()` / `logRequestContext()` | 展示口径和真实压缩口径不一致 |
| DeepSeek/Kimi 会回传历史 reasoning | provider 的 `shouldSendReasoningContentInRequestHistory()` | reasoning 实际占窗，但当前估算为 0 |

### 0.2 现有工具输出边界

“已有工具限流”不是统一 hard cap，实施与验收必须按真实边界区分：

| 工具/路径 | 当前边界 | Phase 90 能恢复什么 |
|-----------|----------|---------------------|
| `execute_command` | 文本硬截到 8,000 chars | 只能恢复这 8,000 chars，后续 stdout 已丢失 |
| `read_file` | 带 offset/limit 时最多 2,000 行；省略范围时 `Files.readString` 无字符上限 | 可把全量读取结果 spill，但不能降低读取期间的峰值内存 |
| `grep_code` | `max_results≤200`、context≤5；`max_chars` 默认 24k、上限 60k，并返回 `partial` | 只能恢复达到这些限制前的结果，保留 `partial` 标记 |
| `glob_files` | `max_results≤200` | 可保存返回的候选列表，200 条之外不可恢复 |
| `web_fetch` | `max_chars` 默认 8,000，但当前没有统一全局 clamp | 大参数返回可被 spill；上游抓取/提取阶段的截断仍不可恢复 |
| `web_search` | 每条 snippet 截到 200 chars，provider/top_k 无统一总字符上限 | 可 spill 聚合结果，但不能恢复 snippet 被截掉的正文 |
| `list_dir` | 当前无总结果字符上限 | 由通用 spill 和 artifact 配额兜底 |
| `load_skill` | body 约 5 KiB 后截断 | 句柄只保存已返回部分，完整 skill 仍走既有查看入口 |
| 动态 MCP 工具 | 无 PaiCLI 通用文本上限 | 是通用 spill 的主要覆盖对象 |

`docs/phase-12-long-context.md` 记录的是历史阶段状态。现行代码已经改为所有模型按 `maxContextWindow` 统一派生参数，且 `AgentBudget` 默认不再使用 `0.8 × window` 作为硬退出线。本设计以代码行为为准，实施时同步修正文档口径。

---

## 1. 需求目标

### 1.1 要解决的三个问题

| # | 问题 | 本期答案 |
|---|------|----------|
| Q1 | 大工具结果如何既不撑爆上下文，又不永久丢信息？ | 工具边界自动落盘；历史只保存固定大小预览、元数据和 opaque handle；用 `read_tool_result` 分片恢复 |
| Q2 | 压缩后为什么本地历史也消失？ | 把 `sourceHistory` 与 `modelView` 分开；摘要只是请求投影，不修改源列表 |
| Q3 | 什么时候应该压缩才接近真实请求大小？ | 统一估算 messages、实际工具 schema、会回传的 reasoning、请求结构开销，并显式扣除回复预留与安全余量 |

### 1.2 可验收目标

| 维度 | 指标/断言 | 通过条件 |
|------|-----------|----------|
| 上下文抑制 | `tool_result_context_tokens` | 超过 spill 阈值的单个文本结果，进入历史的投影大小固定受控，不随原文线性增长 |
| 可恢复性 | `artifact_sha256` | 配额内成功落盘的结果，经句柄分片拼接后与 ToolRegistry 边界文本的 Unicode 规范化值逐 code point 一致 |
| 历史完整性 | `source_history_unchanged` | 单次 compactor 投影前后源消息数量、顺序、role、tool call id 和内容一致；既有 system refresh、image prune 与显式 `/clear` 作为单独契约测试 |
| 协议完整性 | `tool_pair_preserved` | 模型视图的切割点仍在 user 边界，不产生孤立的 assistant tool call 或 tool result |
| 预算完整性 | `request_accounting` | 压缩判定至少包含消息正文、图片估算、tool call 元数据、provider 实际回传的 reasoning、工具 wire schema、回复预留和安全余量 |
| 三路径一致 | ReAct / Plan / Worker SubAgent | 同一输入与工具集合得到同一预算口径；大结果都返回相同 envelope 结构 |
| 降级安全 | spill/summary 失败 | 不把超大原文重新塞回请求；明确标记不可恢复或返回受控的 context exceeded 错误 |

### 1.3 非目标

- 不在本期持久化完整 CLI/Runtime 会话，也不让进程重启后自动恢复 `conversationHistory`。当前 `RuntimeThreadStore` 只保存 thread/event，Durable Task 只保存 prompt/result/status。
- 不移除工具生产阶段的资源上限。比如 `execute_command` 已在 8,000 字符处截断，本期只能保存它实际返回给 ToolRegistry 的文本，不能恢复从未被捕获的后续 stdout。
- 不把命令、HTTP 或 MCP 响应改成边读边写文件；工具当前仍先构造 `String`，所以本期解决上下文爆炸，不解决工具执行期间的峰值内存。
- 不处理图片二进制工件。`imageParts` 首次注入仍可能占用较多窗口，继续走现有多模态消息、token 估算与历史图片省略逻辑；hard ceiling 仍会拦住超窗请求，但本期的“可恢复文本”承诺不覆盖图片原始 payload。
- 不把大 tool-call arguments 改成句柄。`write_file` 等调用参数仍必须按 provider 协议保留在 assistant tool call 中；完整请求预算会识别其占用并在必要时受控失败，参数工件化属于后续独立设计。
- 不引入向量索引、语义检索或自动把全文重新注入上下文；恢复必须是按句柄、按范围的显式工具调用。
- 不更改 prompt cache 的窗口语义。cached input 影响成本，不减少模型上下文占用。
- 不让摘要替代审计日志、Side-Git 快照或长期记忆。

---

## 2. 架构设计

### 2.1 总体结构：源历史、模型视图和外部工件分离

```text
工具执行
  │
  ▼
ToolRegistry.executeTracedInvocation()
  │  ToolOutput.text（工具边界收到的完整逻辑文本）
  ▼
ToolResultArtifactStore.project()
  ├── 小结果 ───────────────────────────────┐
  └── 大结果 → UTF-8 原文落盘 → preview + handle ┤
                                                 ▼
                                      sourceHistory / sourceMessages
                                      （保存小结果全文，或大结果 envelope）
                                                 │
                      ┌──────────────────────────┴──────────────────────────┐
                      │                                                     │
                      ▼                                                     ▼
          ConversationHistoryCompactor                         read_tool_result(handle,
          + HistoryProjectionState                              offset, max_chars)
                      │                                                     │
                      ▼                                                     └─→ 分片工具结果
              modelView（摘要 + recent）
                      │
                      ▼
          RequestContextEstimator
          messages + reasoning + tools schema
          + response reserve + safety margin
                      │
                      ▼
                LlmClient.chat()
```

关键决策：

1. **限流与落盘是两层，不是二选一。** 已有 producer 分页/hard cap 继续提供局部资源保护；没有统一上限的工具由 spill 与 artifact 配额兜底。spill 防止 LLM 上下文爆炸并提供“相对于 ToolRegistry 边界”的恢复能力。
2. **句柄必须是 opaque。** 模型只看到 `tool-result://...`，不能传任意本地路径；专用读取工具负责校验作用域和范围。
3. **大结果正文不继续留在 `ToolExecutionResult`。** 落盘成功后，`result` 变成模型安全的 envelope，完整字符串在本轮包装完成后即可释放；同时保留有界 `displayPreview` 与结构化 `resultMetadata`，供 UI 摘要、`partial` 判断等非历史消费者使用，不能再靠解析完整 `result`。
4. **压缩器返回 view，不改 source。** 源历史仍是后续摘要、调试和重新投影的事实来源；摘要 checkpoint 只是派生缓存。
5. **预算按即将序列化的请求计算。** system prompt 已属于 messages，不再一边按实际内容计数、一边又固定扣一份 `reservedSystem`。
6. **压缩是 soft trigger，发送最终有 hard ceiling。** Phase 90.3 已在 provider 前拒绝超限候选并返回 `CONTEXT_VIEW_BUILD_FAILED`；transport 对 OpenAI-compatible direct caller 另有第二道相同防线。

### 2.2 建议代码结构

```text
src/main/java/com/paicli/
├── context/
│   ├── RequestContextEstimator.java       # 完整请求估算与判定
│   ├── RequestContextBudget.java          # 分项结果、soft trigger、hard ceiling
│   └── ContextViewBuildException.java     # provider 前受控超限失败
├── llm/
│   └── LlmRequestWireFormat.java          # transport/estimator 共用 schema/envelope
├── memory/
│   ├── ConversationHistoryCompactor.java  # 从“原地修改”改为“返回投影视图”
│   ├── HistoryProjectionState.java        # 当前摘要 checkpoint
│   ├── HistoryProjectionResult.java       # 不可变 model view + 投影元数据
│   └── SummaryCheckpoint.java             # covered prefix 与策略身份
└── tool/
    ├── ToolResultArtifactStore.java       # 原文写入、读取、清理、配额
    ├── ToolResultArtifactRef.java         # handle、长度、digest、创建时间
    ├── ToolResultMetadata.java            # partial/source/首行等有界结构化状态
    ├── ToolResultProjection.java          # context envelope + display metadata
    ├── ToolResultAccumulator.java         # Plan fallback 的有界结果索引
    └── ToolRegistry.java                  # 统一投影入口 + read_tool_result 注册

src/test/java/com/paicli/
├── context/RequestContextEstimatorTest.java
├── memory/ConversationHistoryCompactorTest.java
└── tool/ToolResultArtifactStoreTest.java
```

不要求把 `ToolRegistry` 在本期拆成多个注册器；新增类只承接通用状态和可独立测试的逻辑，避免继续把落盘、预算和摘要细节塞进三个 Agent。

### 2.3 工件目录

默认目录放在用户级 PaiCLI 数据目录，而不是项目工作区：

```text
~/.paicli/context-artifacts/
└── <project-hash>/
    └── <session-id>/
        ├── manifest.jsonl
        ├── <artifact-id>.txt
        └── <artifact-id>.json
```

理由：

- 不污染用户仓库，也不需要修改项目 `.gitignore`；
- 不受项目 `PathGuard` 限制，读取统一经过 opaque handle；
- 与 `~/.paicli/memory`、`~/.paicli/runtime`、`~/.paicli/tasks` 的现有用户级数据布局一致；
- `project-hash` 只用于隔离，不把绝对项目路径写进 handle。

scope 生成与传播规则：

- `project-hash` 由 canonical project root 计算，符号链接归一化后再 hash；
- CLI 主会话装配 primary `ToolRegistry` 时生成一次随机 `artifact-session-id`，ReAct、Plan、Team 和 nested Explore 继承同一个 non-owning `ToolResultArtifactStore` 与 scope；
- `ToolRegistry.readOnlyExploreView()` 不得新建或关闭主 store，只增加只读工具视图；
- Runtime/headless 每个隔离执行生成独立 session；若装配层已有 runtime thread id 或 durable task id，可作为 scope seed，但不能跨 thread/task 读取；
- 跨 project 或跨未授权 session 的 handle 一律返回 `HANDLE_SCOPE_MISMATCH`。

物理写入使用同目录临时文件 + 原子 move；元数据成功写完后才发布 handle。日志与 trace 只记录 artifact id、长度、digest 和状态，不记录完整正文或绝对存储路径。

### 2.4 工件元数据契约

```jsonc
{
  "schema_version": 1,
  "artifact_id": "01J...",
  "handle": "tool-result://<session-id>/<artifact-id>",
  "project_hash": "sha256:...",
  "session_id": "...",
  "tool_name": "mcp__server__tool",
  "tool_call_id": "call_123",
  "created_at": "2026-08-10T10:00:00Z",
  "charset": "UTF-8",
  "chars": 128400,       // Unicode code point 数，不按 UTF-16 surrogate 拆分
  "bytes": 132100,
  "sha256": "...",
  "captured_complete": true,
  "unicode_normalized": false
}
```

说明：

- `captured_complete=true` 只表示 `.txt` 与 ToolRegistry 边界文本经过下述 Unicode 规范化后的值一致，不承诺上游命令、HTTP 或 MCP server 没有先截断。
- 元数据不保存完整 tool arguments，避免把 token、密码或大段写入内容复制到第二个文件。
- handle 不含项目路径、工具参数或正文摘要。
- 写盘前验证 Unicode；孤立 surrogate 统一替换为 `U+FFFD`，并把 `unicode_normalized` 置为 true。digest、预览和分页都以规范化后的文本为准；已知 producer 的 `substring` 截断同步改为 code-point safe，避免主动制造非法边界。

每个 session 还维护一个有界、原子更新的 artifact manifest，manifest 自身有稳定 handle。它只列出 artifact handle、tool、长度、digest、创建时间和可用状态，不包含正文。历史摘要不必内联无限增长的句柄列表，只需始终保留 manifest handle，并可附最近若干 artifact 的短索引；模型需要旧句柄时用同一个 `read_tool_result` 分页读取 manifest。

### 2.5 模型侧大结果 envelope

落盘成功后，`ToolExecutionResult.result()` 使用稳定、可读的文本契约：

```text
[工具结果已落盘]
tool: mcp__server__large_query
handle: tool-result://01JSESSION/01JARTIFACT
original_chars: 128400
sha256: 3c2d...
preview: head=4000 chars, tail=1000 chars
读取更多: read_tool_result {"handle":"tool-result://01JSESSION/01JARTIFACT","offset":0,"max_chars":12000}

--- preview head ---
...
--- omitted: 123400 chars ---
--- preview tail ---
...
```

handle、长度和读取方式必须出现在前 500 字符内，因为 `MemoryManager.addToolResult()` 目前仍只保留工具结果前 500 字符。预览采用 head + tail，而不是只取开头：命令错误、统计汇总和结束标记经常位于末尾。

这里的 500 字符只约束 `MemoryManager` 的短期记忆副本，不是 conversationHistory 的上限。为保持现有 UI 与确定性预检行为，projection 还要在释放原文前生成有界 `displayPreview` 和结构化 `resultMetadata`（至少含 `partial`、`producerTruncated`、首行摘要、source label）。`Agent.emitToolResultSummary()`、`webSearchSummary()`、`webFetchSummary()` 与 `maybeInjectExploreSuggestion()` 改读这些字段，不能依赖 envelope 恰好保留某个字符串。

若写盘失败、配额不足或结果超过允许保存的单工件上限，返回固定大小预览并明确标记：

```text
[工具大结果未完整保留: artifact_write_failed]
handle: unavailable
original_chars: 128400
...
```

此降级会丢信息，但仍优先保证不会把超大正文重新塞回上下文；同时写 warning 和结构化计数，便于定位存储故障。

### 2.6 `read_tool_result` 工具契约

```jsonc
{
  "handle": "tool-result://<session-id>/<artifact-id>",
  "offset": 0,          // Unicode code point offset
  "max_chars": 12000   // 默认 12000，上限 24000，同样按 code point 计数
}
```

返回：

```text
[工具结果分片]
handle: tool-result://01JSESSION/01JARTIFACT
range: [0, 12000)
next_offset: 12000
eof: false
sha256: 3c2d...

--- content ---
...
```

约束：

- 只接受当前 ToolRegistry/会话可见的合法 handle，不接受文件路径；
- 错误首行使用稳定枚举：`INVALID_HANDLE`、`HANDLE_SCOPE_MISMATCH`、`ARTIFACT_NOT_FOUND`、`ARTIFACT_EXPIRED`、`ARTIFACT_DIGEST_MISMATCH`、`OFFSET_OUT_OF_RANGE`；
- 每次最多 24,000 个 Unicode code points，切片不得拆开 emoji 等 surrogate pair；禁止“一次读取全文”绕过上下文保护；
- `read_tool_result` 是 spill-exempt 工具，其自身输出已经严格分页，不能再次生成新句柄造成递归落盘；
- 同一工具也可读取 session manifest 的稳定 handle，从而发现已经被旧摘要覆盖的 artifact；
- `ToolRegistry.readOnlyExploreView()` 必须共享同一个 artifact store，并把 `read_tool_result` 加入只读白名单，否则探索 SubAgent 遇到 spill 后无法继续读取自己的结果；
- 该工具只读且不经过项目 `PathGuard`，但必须使用独立的 handle scope 校验，不纳入危险工具审批集合。

### 2.7 存储生命周期与配额

第一版采用保守默认值：

| 项目 | 默认值 | 行为 |
|------|--------|------|
| spill 阈值 | 估算 4,096 tokens | 小于阈值直接内联，大于等于阈值尝试落盘 |
| 预览 | head 4,000 + tail 1,000 chars | envelope 总大小稳定受控 |
| 单工件上限 | 32 MiB | 超过后只返回预览并标记 quota/size failure |
| 会话总上限 | 256 MiB | 达到上限后不再写新工件 |
| 保留时间 | 7 天 | 启动时 best-effort 清理过期且非活动 session |
| 显式 `/clear` | 清理主会话历史、projection state 并轮换 artifact session | 旧 handle 立即失效，活动写入结束后 best-effort 删除旧 session；长期记忆保持不变 |

这些上限保护本地磁盘，但意味着“可恢复”承诺只覆盖**工具边界已收到、成功写盘且处于配额/保留期内**的文本。这个边界必须在 README 和 `/context` 中明确展示。

第一版对外暴露少量必要配置，其余预览/配额常量先固定，避免配置面膨胀：

| system property | environment | 默认值 |
|-----------------|-------------|--------|
| `paicli.tool.result.dir` | `PAICLI_TOOL_RESULT_DIR` | `~/.paicli/context-artifacts` |
| `paicli.tool.result.spill.tokens` | `PAICLI_TOOL_RESULT_SPILL_TOKENS` | `4096` |
| `paicli.tool.result.retention.days` | `PAICLI_TOOL_RESULT_RETENTION_DAYS` | `7` |

以上同样通过 `ConfigValueResolver` 读取：system property > environment > 项目 `.env` > 用户 `~/.env` > 默认值。

写盘属于工具调用的收尾步骤，继续受父 `CancellationContext` 与本地工具 deadline 约束：写前/发布 handle 前都检查取消状态；取消、超时或失败时删除临时文件，不发布半成品。单工件上限使本地文件 I/O 有明确边界，但第一版不声称普通 `Files.writeString` 能被线程中断立即终止。

`/clear` 需要新增 `Main → Agent/ToolRegistry` 的显式 clear-session hook：先拒绝新 artifact 写入并等待当前批次结束/取消，再轮换 session id，最后异步清理旧目录。Orchestrator 内部为了复用 Worker 而调用的 `SubAgent.clearHistory()` 只重置消息与 projection state，不轮换或关闭共享 store。

---

## 3. 非破坏性 ConversationHistory 压缩

### 3.1 数据模型

每个实际维护对话的执行单元持有两份状态：

```java
final class ConversationState {
    List<LlmClient.Message> sourceHistory;       // 事实来源，不被 compactor 替换
    HistoryProjectionState projectionState;      // 摘要 checkpoint + append-only 观测
}

record HistoryProjectionState(
    SummaryCheckpoint checkpoint,
    int observedSystemEnd,
    int observedSourceSize,
    String observedSourceDigest
) {}

record SummaryCheckpoint(
    int systemEnd,
    int coveredUntilExclusive,
    String coveredPrefixDigest,
    int sourceSizeAtCheckpoint,
    String sourceAppendBaselineDigest,
    String summary,
    String summaryPolicyVersion,
    int retainRecentRounds,
    String provider,
    String model,
    boolean reasoningReplayEnabled,
    String artifactManifestHandle,
    int artifactManifestSchemaVersion
) {}

record HistoryProjectionResult(
    List<LlmClient.Message> modelMessages,
    HistoryProjectionState nextState,
    boolean compacted,
    boolean checkpointAdvanced,
    int coveredFromInclusive,
    int coveredUntilExclusive,
    String coveredPrefixDigest,
    int estimatedTokensBefore,
    int estimatedTokensAfter,
    boolean summaryFailed
) {}
```

`HistoryProjectionResult` 继续保留消息级摘要前后估算用于兼容；Phase 90.3 的完整分项由独立 `RequestContextBudget` 承载，不能把两者混为同一口径。

这里的 `sourceHistory` 是“未被摘要替换的消息源”，不是绝对不可变的审计日志：

- 大工具结果从进入历史开始就是“preview + handle”，正文在 artifact store；
- 现有历史图片 payload 省略仍可修改旧 image message；
- system prompt 的 memory/skill 动态更新仍可替换第 0 条；
- 用户显式 `/clear` 仍会清空当前会话。

Phase 90 的硬约束是：`ConversationHistoryCompactor` 不得再调用 `sourceHistory.clear()` 或用摘要覆盖源消息。

`coveredPrefixDigest` 只覆盖 `[systemEnd, coveredUntilExclusive)` 的稳定消息序列，明确排除动态刷新且不会进入摘要输入的 `sourceHistory[0]` system prompt。checkpoint 还保存创建时 source size / 全 source 基线 digest，projection state 保存最近一次成功观察到的 source size / digest，用于证明两次投影间只发生尾部 append；`retainRecentRounds` 也作为策略字段参与校验。system 的实际 token 每轮仍重新估算；summary template/provider/model/reasoning replay mode 则作为 checkpoint 独立版本字段参与有效性判断。

### 3.2 模型视图算法

```text
1. 使用 `RequestContextEstimator` 对 rawView + 本轮实际 tools 计算完整请求估算
2. 若 `estimatedInput(rawView + tools) < softTrigger`：直接返回 sourceHistory 的不可变副本；已有 checkpoint 可保留为缓存但本轮不强制使用
3. 若 rawView 需要投影且已有 checkpoint：先构造 [system] + [summary] + [ack] + [未覆盖 tail]
4. 若 `estimatedInput(view + tools) < softTrigger`：复用 checkpoint，不再次调用摘要 LLM
5. 若完整 `estimatedInput(view + tools)` 达到 softTrigger：
   a. 在 user message 边界选择更靠后的 split
   b. 保留最近 3 个 user 起算的完整尾部
   c. 只有 split > coveredUntilExclusive 时，才用“旧 summary + [coveredUntilExclusive, split) 新覆盖段”增量生成新 summary
   d. 更新 coveredUntilExclusive 与 prefix digest
6. Phase 90.2 对新 view 记录消息级前后估算；split 已是“保留最近 3 个 user”的最远合法边界，不重复摘要同一前缀
7. 对最终完整 request 做 hard-ceiling 校验；最小尾部仍超限时本地失败，不调用 provider
```

如果找不到 `split > coveredUntilExclusive`（例如尾部 user 轮次不足或最小保留尾部已经占满窗口），不得重复摘要同一前缀或伪造 checkpoint：复用包含完整 tail 的旧 candidate，再由完整请求 hard ceiling 决定是否允许发送。

模型视图继续沿用 Phase 24 的结构化 Markdown 摘要，并保持当前兼容形态：

```text
[system]
[user: 已压缩的历史对话摘要 + summary]
[assistant: 好的，我已了解之前的上下文，请继续。]
[最近若干完整消息]
```

切割点仍只允许落在 user message 边界。最近尾部中的 assistant tool call、对应 tool result 和后续 assistant 必须整体保留。

summary user message 末尾再由程序确定性追加一个“可恢复工具工件索引”块，至少包含 session manifest handle、可用 artifact 数和最近若干 handle/digest；它不依赖摘要 LLM 是否忠实复制。Phase 24 的 `## Files and Code Sections` 同时增加“原样保留 `tool-result://...`、digest 和 captured status”的约束。这样旧 tool envelope 被摘要覆盖后，模型仍能先读 manifest，再恢复任意在保留期内的正文。

### 3.3 checkpoint 有效性

`coveredPrefixDigest` 对已覆盖源消息的稳定序列化计算摘要。出现以下情况时 checkpoint 失效并重建：

- `/clear`；
- 已覆盖范围内消息被历史图片省略等既有逻辑改写；ReAct/SubAgent 的 image prune 必须发生在 checkpoint 校验之前；
- system 之外的消息被插入、删除、重排或覆盖；append baseline / 最近观察 digest 用来区分合法尾部增长与 retained tail 改写；
- model/provider 切换导致 summary 策略版本变化；
- 结构化摘要模板版本变化。

只在 source 尾部 append 新消息时，旧 checkpoint 可直接复用。新增 user/LSP/tool hint/tool result 都先 append，再投影；任何插入、删除、重排或覆盖区改写都先 invalidate。Plan task 没有跨 task 历史图片生命周期：本 task 内的 image message 正常参与预算，task 结束随局部 state 丢弃，不新增跨 task prune。

session manifest handle 在同一 artifact session 内稳定，新增 artifact 只更新 manifest 内容与动态索引块，不使 summary checkpoint 失效；每次构造 model view 都重新读取 manifest 的 count/latest metadata。显式 `/clear` 会轮换 session handle，并与 source/projection state 一起重置。

projection state 采用原子提交：只有完整 model view 构建、artifact index 追加和 hard ceiling 校验都成功后，调用方才替换 `projectionState`。失败保留上一个 checkpoint，但不得用不含当前 source tail 的旧 view 静默调用业务 LLM。

### 3.4 摘要失败策略

当前失败分级：

1. Phase 90.2：有有效旧 checkpoint 时使用“旧 summary + 从旧 covered boundary 开始的完整当前 tail”；没有有效 checkpoint 时使用当前 source 的不可变快照；两者都不提交失败的摘要。
2. Phase 90.3：若任何包含当前 tail 的 candidate 都超过 hard ceiling，则不发送请求并返回明确 `CONTEXT_VIEW_BUILD_FAILED`。旧 checkpoint 不能用于丢掉新消息后继续请求。

失败绝不能修改 `sourceHistory` 或覆盖最后一个有效 checkpoint。

摘要源输入最多 60,000 Unicode code points，输出限制为 estimated 4,096 tokens / 16,000 chars（先到者生效）；空白、超限或缺少必要结构标题均视为失败。摘要不序列化 `reasoningContent`，避免隐藏 reasoning 暴露到普通 user summary；摘要请求自身也由独立 `RequestContextEstimator` 执行 response reserve / hard ceiling 检查。

摘要失败不会污染 source/state；raw/旧 checkpoint candidate 仍必须通过最终 hard ceiling。三条业务路径返回明确 `CONTEXT_VIEW_BUILD_FAILED`，headless Runtime/Durable Task 通过异常映射为 failed。

### 3.5 三条路径接入

| 路径 | source 状态 | projection 状态 | `chat()` 入参 |
|------|-------------|------------------|--------------|
| ReAct | Agent 字段，跨用户轮次保留 | Agent 字段；`/clear` 重置 | `projection.modelView()` |
| Plan-and-Execute | 每个 task 的局部 `messages` | 每个 task 的局部 state | task 的 `projection.modelView()` |
| Multi-Agent Worker | 每个 SubAgent 的历史字段 | 每个 SubAgent 字段；内部 `clearHistory()` 重置 | `projection.modelView()` |

`AgentOrchestrator` 本身不新增一份 conversationHistory；Planner/Reviewer 没有工具时，估算器传入空 tools，Worker 使用实际工具定义。

调试 API 语义明确分开：

- `getConversationHistory()`：返回 source history 的浅拷贝；
- 新增 `getLastModelConversationView()`：返回最近一次真正传给 LLM 的 view；
- `/context` 与底部 `ctx`：默认显示“下一轮模型 view 的估算”，同时可列出 source history 估算，避免把两者混为一谈。

---

## 4. 完整请求级上下文预算

### 4.1 当前模型窗口与统一口径

| Provider client | 当前固定 window 声明 | 历史 reasoning replay | 完整 request accounting |
|----------|------------|----------------------|-----------------------------|
| GLM | 200,000 | 否 | 是 |
| DeepSeek | 1,000,000 | 是 | 是 |
| Step | 256,000 | 否 | 是，含 reasoning request fields |
| Kimi | 256,000 | 是 | 是 |
| FreeLLMAPI | 128,000 | 否 | 是 |

soft trigger 不再是 `window × 90%` 的固定数字，而是 `(window - response reserve - safety margin) × 90%`；实际 tools schema 与需要 replay 的历史 reasoning 都参与每个候选 view 的判定。

当前 `TokenBudget` 虽定义了固定 system/tools/response reserve，但 conversationHistory 压缩路径没有使用它；`isWithinBudget(messages)` 还可能把 messages 中已经存在的 system 与固定 `reservedSystem` 重复扣除。Phase 90 不继续混用“历史预算”和“完整请求预算”，而是引入单独的 request accounting。

现行 `TokenBudget` 默认值是 system=500、tools=800、response=2,000；它们不会被直接改写成下文 Phase 90 的 8,192 response reserve。新值属于独立 request accounting，旧 `TokenBudget` 继续服务 usage/shortTermMemory 兼容路径。表中的 window 也是当前 client 常量声明，并不自动校验用户配置的每个具体模型是否真的支持该窗口。

### 4.2 统一公式

```text
window = ContextProfile.from(llmClient).maxContextWindow()  # 含 null=128k 与 min 8k 归一化

estimatedInput =
    messageContentTokens        # 含实际 system/user/assistant/tool 文本与图片估算
  + replayedReasoningTokens     # 仅 provider 真正回传历史 reasoning 时计入
  + toolCallTokens              # name/id/arguments/tool_call_id
  + messageFramingTokens        # 每条 message 的 role/分隔符/tool-call wrapper
  + toolsSchemaWireTokens       # 实际 tools wire shape，不是固定 800
  + requestEnvelopeTokens       # body 级 model/stream/provider 自定义字段，不重复 message framing

configuredSafety = validNonNegative(config, default=max(2,048, window × 1%))
safetyMarginTokens = min(configuredSafety, floor(window × 10%))
configuredResponseReserve = validNonNegative(config, default=8,192)
responseReserveTokens = min(configuredResponseReserve, floor(window × 25%))
hardInputCeiling = window - responseReserveTokens - safetyMarginTokens
softCompressionTrigger = floor(hardInputCeiling × 0.90)

needsProjection = estimatedInput >= softCompressionTrigger
safeToSend = estimatedInput <= hardInputCeiling
```

关键点：

- system prompt 已按实际文本计入 `messageContentTokens`，不再额外固定扣 system；
- tools schema 为 0 当且仅当本次 `chat()` 真的传 `null/empty tools`；
- DeepSeek/Kimi 的历史 `reasoningContent` 按实际文本估算，GLM/Step/Free 不回传时不计历史 reasoning；
- “不 replay 历史 reasoning”不等于模型不生成 reasoning；Step 的 `reasoning_format` / `reasoning_effort` 等 provider 自定义请求字段计入 request envelope，无法精确估算的部分由 safety margin 覆盖；
- 下一轮新生成的 content + reasoning 共用 `responseReserveTokens`；
- cached input 仍计入 `estimatedInput`，只在成本统计中单列 cached；
- ReAct/SubAgent 的 `AgentBudget` 累计 input/output 退出预算保持独立；Plan task 仍使用既有迭代上限和 usage 统计。两者都不能代替单次请求窗口检查。

`softCompressionTrigger` 改为“扣除 reserve 后的 hard ceiling × 90%”，这是 Phase 90 的**有意行为变化**，不再等于旧的 `window × 90%`。相应 `ContextProfileTest`、`/context` 与 Phase 12 文档必须更新。负数、非数字配置回退默认值；0 表示显式不预留；最后仍验证 `response + safety < window`。上面的比例 clamp 保证最小 8k effective window 下 hard ceiling 至少保留 65%，也防止超大配置吃完整个窗口。

### 4.3 provider 能力对齐

在 `LlmClient` 增加公开能力声明，例如：

```java
default boolean replaysReasoningContent() {
    return false;
}
```

DeepSeek、Kimi 返回 `true`；`TracingLlmClient` 必须透传。`AbstractOpenAiCompatibleClient` 的请求构造和 `RequestContextEstimator` 共用该能力，避免一个发送 reasoning、另一个却判断不发送。

工具 schema 估算也要按真实 wire shape：

```jsonc
{
  "type": "function",
  "function": {
    "name": "...",
    "description": "...",
    "parameters": {}
  }
}
```

当前 `/context` 直接序列化 `List<LlmClient.Tool>`，少了上述外层包装。实施时提取一个供 HTTP request builder 和 estimator 共用的 schema formatter，或至少用契约测试保证两者结构一致。

### 4.4 估算精度边界

PaiCLI 当前使用 `MemoryEntry.estimateTokens()` 的启发式估算，而不是各 provider 的官方 tokenizer。因此：

- 所有显示都标记为 `estimated`；
- `safetyMarginTokens` 用于覆盖 tokenizer 与 JSON framing 偏差；
- provider 返回的 `prompt_tokens` 用于日志对比和后续校准，不反向修改已经完成的请求；
- 单元测试验证“组成项完整、单调、边界稳定”，不声称与 provider token 数逐 token 相等。

第一版建议：

| system property / environment | 默认值 | 说明 |
|-------------------------------|--------|------|
| `paicli.context.response.reserve.tokens` / `PAICLI_CONTEXT_RESPONSE_RESERVE_TOKENS` | 8,192，再 clamp 到 `window × 25%` | 仅作为本地窗口预留；本期不强制向 provider 注入 `max_tokens` |
| `paicli.context.safety.margin.tokens` / `PAICLI_CONTEXT_SAFETY_MARGIN_TOKENS` | `max(2,048, window × 1%)`，再 clamp 到 `window × 10%` | 补偿估算误差与少量请求结构 |
| compression ratio | 0.90 | 沿用 ratio，但基准从 raw window 改为 hard input ceiling |

配置读取沿用 `ConfigValueResolver` 的优先级：system property > environment > 项目 `.env` > 用户 `~/.env` > 默认值。实施时同步 `.env.example`。

本地 reserve 不是 provider 输出硬限制；如果模型实际 completion/reasoning 超过预留，已完成请求照常记录真实 usage，下一轮再按增长后的 source/model view 重新估算并可能触发压缩或 hard failure。

### 4.5 `/context` 新口径

建议输出：

```text
Context Usage (estimated)   model: kimi-k2.6   window: 256k

  Source history:        148k   # 未被 compactor 替换的消息源
  Model view messages:    42k   # 下一轮真正发送的摘要视图
    ├─ replayed reasoning  6k
    └─ tool messages       8k
  Tools wire schema:      13k
  Framing / envelope:      1k
  Estimated input:        56k
  Response reserve:        8k
  Safety margin:         2.6k
  Budget footprint:      66.6k / 256k   # input + reserve + safety
  Soft trigger:           221k
  Hard input ceiling:     245k

  Tool artifacts: 3 files / 1.8 MiB / retention 7d
```

底部状态栏 `ctx` 继续表示下一轮仍会带入模型的 estimated input，使用 `modelView + tools schema + framing/envelope` 口径；response reserve/safety 单列为预算，不伪装成已经输入的 token，也不显示累计 in/out。

---

## 5. 详细执行链路

### 5.1 工具结果落盘

```java
ToolExecutionResult executeTracedInvocation(ToolInvocation invocation) {
    ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());

    ToolResultProjection projection;
    try {
        projection = contextResultPolicy.projectTopLevel(
            invocation.name(), invocation.id(), output.text());
    } catch (ArtifactProjectionException e) {
        // 用仍在当前栈上的原文生成 bounded preview 后立即释放；不回灌全文
        projection = contextResultPolicy.projectionFailure(output.text(), e.code());
    }

    ToolExecutionResult result = ToolExecutionResult.completed(
        invocation,
        projection.contextText(),   // 小结果全文，或大结果 envelope
        projection.displayPreview(),
        projection.resultMetadata(),
        output.imageParts(),
        projection.artifactRef(),
        elapsedMillis
    );

    toolRun.output(result.result()); // trace 不复制大原文
    return result;
}
```

`ToolExecutionResult` 增加可空 `artifactRef`、有界 `displayPreview`、`resultMetadata` 与 `spilled()`，但保留 id/name/arguments/timedOut/imageParts 语义。Agent、Plan、SubAgent 仍按原顺序回灌 `result()`，UI/partial 检测改用 metadata。现有测试或扩展点若直接调用 record 构造器，提供兼容 factory/构造器或统一更新调用点，避免无关编译回归。

HITL/legacy 兼容要求：**顶层、将进入模型上下文的调用**先完整经过 `executeToolOutput()`（含 HITL override、legacy override、策略与审计），再调用同一个 `contextResultPolicy`。projection 异常与工具执行异常分开处理：前者保留工具成功语义并返回 `artifact_write_failed` envelope，后者仍走既有 tool failure。public `executeTool()` 本身不是统一保证边界，任何把它的返回值送进 history 的调用方必须显式走同一 policy。

StepSearch 等工具内部为实现外层 `web_search`/`web_fetch` 而嵌套调用 `executeToolOutput()` 时保持 raw 内部值，不能在内层提前 projection；只在最外层 `ToolInvocation` 形成一次 artifact/envelope，避免句柄套句柄。

Plan 的 `allResults` 另改为有界 `ToolResultAccumulator`：只累计每次调用的首行状态、artifact handle/digest 和少量小结果摘要，总字符上限建议 24,000；达到上限后写 `partial` 与 session manifest handle。最终模型 content 为空或达到迭代上限时，fallback/memory 只接收该有界汇总，不能把多个 envelope 的 head/tail 预览再次无界拼接。

### 5.2 freshness preflight

ReAct 的时效性预检当前直接调用 `executeTool("web_search", ...)`，再把字符串嵌入 user message，绕过 `executeTools()`。Phase 90 固定改为构造单元素 `ToolInvocation` 并走 `executeTools()`，再把返回的 context result 嵌入 user message；不允许这里单独复制一套 projection 逻辑，否则“所有进入模型的工具结果受控”仍会留下例外。

普通 CLI 命令若只是把工具结果直接显示给用户、从不进入 conversationHistory，可以继续使用 raw display 路径；只要结果将进入模型上下文，就必须先经过 projection。

### 5.3 请求前投影

```java
List<LlmClient.Tool> tools = shouldUseTools()
        ? toolRegistry.getToolDefinitions()
        : List.of();

HistoryProjectionResult projection = historyCompactor.project(
        sourceHistory,
        projectionState,
        triggerTokens,
        toolRegistry.getToolResultArtifactIndex());

projectionState = projection.nextState();
LlmClient.ChatResponse response = llmClient.chat(
        projection.modelMessages(),
        tools,
        streamRenderer);
```

业务响应、tool call assistant message 和工具 envelope 只 append 到 `sourceHistory`。永远不要把 summary/ack 反写进 source；它们只存在于 `modelView`。

### 5.4 再次读取大结果

```text
LLM 看到 preview + handle
→ 判断缺少关键区间
→ read_tool_result(handle, offset=12000, max_chars=12000)
→ ToolRegistry 返回一个受限分片
→ 分片作为普通 tool message append 到 sourceHistory
→ 下一轮预算重新估算；必要时生成/推进 summary checkpoint
```

读取分片本身是用户选择“把哪部分信息重新带回上下文”的显式动作。系统不自动全文回灌，也不在压缩时隐式读取所有句柄。

---

## 6. 分阶段开发计划

### Phase 90.1：可恢复工具结果工件 ✅ 已交付

范围：

- `ToolResultArtifactStore`、metadata schema、原子写入、digest、TTL/配额；
- `ToolExecutionResult` 增加 artifact metadata；
- `ToolRegistry.executeTracedInvocation()` 统一投影；
- 新增 `read_tool_result` 与分页协议；
- freshness preflight、nested StepSearch、`readOnlyExploreView()` 对齐；
- UI/`partial` 判断迁移到有界 metadata，Plan `allResults` 改为有界 accumulator；
- Unicode 规范化与已知 producer 的 code-point safe 截断；
- project/session scope 的 real-path containment、symlink 拒绝、metadata/body fail-closed 校验；
- 活动工具批次 session lease、TUI 运行期 `/clear` 拒绝、项目根切换时清理旧 handle 上下文；
- prompt、工具说明、README、AGENTS 和 `.env.example` 联动。

验收结果（2026-08-12）：已用 100k+ Unicode/MCP/legacy 工具结果验证固定大小 envelope、UTF-8 metadata、session manifest、code-point 分页重组与 SHA-256；同时覆盖 freshness 预检、read-only Explore 共享 scope、nested StepSearch 不重复投影、图片 parts 保留、`/clear` 轮换 session、项目切换、活动 batch/clear 串行化、损坏 metadata/digest、symlink 逃逸、写盘/配额失败降级和 Plan 有界 fallback。Phase 90.1 定向联合回归与 Maven package 通过；本机无创建 symlink 的权限，2 个 symlink 攻击回归按前置条件跳过。`-Pquick` 执行 835 项，其中 15 项仍因既有 Windows 路径/CRLF、RAG 临时目录与受限 DNS 环境失败，不属于本期改动路径。

### Phase 90.2：source history / model view 分离 ✅ 已交付

范围：

- `ConversationHistoryCompactor.compactIfNeeded(List, int)` 迁移为非破坏性的 `project(...)`；
- 引入 `HistoryProjectionState` / `SummaryCheckpoint`；
- ReAct、Plan task、SubAgent 三条 `chat()` 调用都传 model view；
- checkpoint 复用、prefix digest 失效、摘要失败保护；
- `getConversationHistory()` 与新增 model view 调试 API 分工。

验收：触发一次和多次压缩后，source history 内容不变；model view 变短；最近 tool call/result 配对完整；摘要失败不污染 source 或有效 checkpoint。

验收结果（2026-08-12）：新增不可变 `HistoryProjectionResult` / `HistoryProjectionState` / `SummaryCheckpoint`；prefix digest 覆盖 role/content/contentParts/tool call/tool_call_id/reasoning，排除动态 system[0]；source append baseline 会拒绝 retained tail 的插入、删除、重排或覆盖。ReAct、Plan task、Worker SubAgent 的业务 `chat()` 均改传 model view。连续增长只总结新覆盖前缀，provider/model/replay/summary/retain policy/manifest scope 变化会使 checkpoint 失效；artifact count/latest 通过有界动态索引刷新。摘要 IOException、空白、超长、缺标题及 reasoning 容器均不提交状态；有效旧 checkpoint 回退仍包含完整当前 tail。定向测试覆盖 source 逐字段不变、嵌套列表不可变、tool pair、DeepSeek/Kimi replay 能力、三路径请求捕获、`/clear`/项目/新任务生命周期与 Plan task 图片生命周期。Maven 定向联合回归与 package 通过；`-Pquick` 当前执行 852 项，仍为既有 Windows URI/路径/CRLF、RAG 临时目录和受限 DNS 导致的 10 failures + 5 errors，4 项按环境前置条件跳过，没有 Phase 90.2 新增失败。

### Phase 90.3：统一请求预算与可见化

范围：

- `RequestContextEstimator` / `RequestContextBudget`；
- 使用 Phase 90.2 已公开的 provider reasoning replay 能力完成请求 token accounting；
- 实际 tools wire schema 估算；
- response reserve、safety margin 与 soft/hard 两级判定；
- `/context`、状态栏 `ctx`、request log 使用同一 accounting；
- 修正 Phase 12、agents-reference、README 中已过时的模式与预算描述。

验收：同一 messages/tools 在 ReAct、Plan task、Worker 三条业务路径得到一致分项；Kimi/DeepSeek 加入 reasoning 后估算上升；动态 MCP schema 变大可提前触发投影；三条业务路径、Planner、摘要与 short-term compressor 超过 hard ceiling 时均不会向 stub/provider 发请求。

验收结果（2026-08-12）：新增不可变 `RequestContextBudget`、统一 `RequestContextEstimator`、受控 `ContextViewBuildException` 与 transport/accounting 共用的 `LlmRequestWireFormat`。完整估算覆盖消息/图片、reasoning replay、tool-call 元数据、实际 tools schema 与 provider envelope；Step 自定义 reasoning fields 和 Tracing wrapper 已对齐。ReAct、Plan task、Worker 在 hard gate 后原子提交 projection state，Planner、摘要与 short-term compressor 同样受保护；OpenAI-compatible transport 提供第二道防线。`/context`、底部 `ctx` 和 request log 已统一。定向测试覆盖配置 clamp/0/非法值、分项单调、wire shape、动态 schema soft trigger、Kimi/DeepSeek replay、三路径与真实 HTTP provider 前拒绝、旧 checkpoint + 完整超限 tail 不提交，以及 Runtime headless failure 映射。核心定向回归 67 项与 Maven package 通过；`-Pquick` 执行 874 项，仍有既有 Windows URI/路径/CRLF 与 RAG 临时目录导致的 9 failures + 5 errors，4 项按环境前置条件跳过，没有 Phase 90.3 新增失败。

### 6.1 实施顺序（已完成）

`90.1 → 90.2 → 90.3`。

实际按该顺序完成：先把新产生的大工具结果变成可恢复句柄，再分离 source/view，最后把所有路径的触发和显示统一到完整请求预算。

---

## 7. 文件联动清单

### 7.1 已新增

| 文件 | 内容 |
|------|------|
| `context/RequestContextEstimator.java` | 估算 messages/reasoning/tool calls/schema/reserve |
| `context/RequestContextBudget.java` | 分项、soft trigger、hard ceiling、诊断文本 |
| `memory/HistoryProjectionState.java` | summary checkpoint 状态 |
| `memory/HistoryProjectionResult.java` | 不可变 model view、covered prefix、消息级 token 前后估算 |
| `memory/SummaryCheckpoint.java` | covered digest 与 policy/provider/replay/manifest 身份 |
| `tool/ToolResultArtifactStore.java` | UTF-8 工件、句柄、分页、TTL、配额 |
| `tool/ToolResultArtifactRef.java` | artifact metadata 值对象 |
| `tool/ToolResultMetadata.java` | UI/partial/producer truncation 的有界结构化状态 |
| `tool/ToolResultProjection.java` | context envelope、display preview 与结构化状态 |
| `tool/ToolResultAccumulator.java` | Plan 多轮工具结果的有界 fallback/index |
| `tool/ToolResultArtifactIndex.java` | manifest handle/count 与最近工件的有界动态快照 |
| 对应测试类 | 边界、失败、并发、路径安全、三路径契约 |

### 7.2 已修改

| 文件/模块 | 修改内容 |
|-----------|----------|
| `ToolRegistry.java` / `ToolOutput.java` | 统一 projection、注册 `read_tool_result`、只读 view 共享 store、保留 image/timedOut 语义 |
| `Agent.java` | freshness preflight 对齐；维护 projection state；chat 使用 model view；`ctx`、request log 与 hard gate 使用完整 request estimator |
| `PlanExecuteAgent.java` | task 局部 projection state；`allResults` 只累计 envelope/小结果 |
| `SubAgent.java` | 维护 projection state；Worker 工具集可读取 handle |
| `ConversationHistoryCompactor.java` | 从原地修改改为返回不可变 view；checkpoint 增量摘要 |
| `ContextProfile.java` | soft trigger 改为 effective hard ceiling 基准，承载归一化 window 与 reserve 配置 |
| `TokenBudget.java` | 保留 usage/shortTermMemory 职责；不再作为完整 request accounting 的隐式替代 |
| `LlmClient.java` 与 provider clients | reasoning replay 能力；Tracing wrapper 透传 |
| `AbstractOpenAiCompatibleClient.java` | schema/request shape 与 estimator 对齐 |
| `PromptAssembler` 资源 | 告知模型 handle 与 `read_tool_result` 的使用规则 |
| `Main.java` | `/clear` 重置主 projection state/工件策略；状态展示接入统一 accounting |
| `.env.example` | 新增 artifact 与 context reserve 配置 |
| `README.md` / `AGENTS.md` / `docs/agents-reference.md` | 工具集、长上下文、恢复边界与验证命令 |
| `ROADMAP.md` | 只在实现交付后更新 Phase 90 状态，不提前宣称已完成 |

---

## 8. 测试与验收

### 8.1 单元测试

`ToolResultArtifactStoreTest`：

- 小结果不落盘；
- 大结果写 `.txt + .json`，原子发布 handle；
- UTF-8 中文、CRLF、emoji 分片拼接与 digest 一致；孤立 surrogate 规范化状态正确；
- head/tail preview 与 omitted 数正确；
- offset 边界、过期、digest mismatch、非法 handle、路径穿越；
- symlink 读写逃逸拒绝、metadata 缺失/损坏 fail-closed、活动 session lease 与 clear 串行化；
- 单工件/会话配额和 I/O failure 降级；
- 并行 4 个结果不覆盖、不乱 handle；
- `read_tool_result` 不发生递归 spill。

`ConversationHistoryCompactorTest`（Phase 90.2 已覆盖）：

- 低于 soft trigger 时 model view 等于 source 的不可变副本；
- 触发压缩后 source 逐字段不变；
- summary + 最近 3 个 user 尾部结构正确；
- tool call/result 在切割边界成对保留；
- 第二次增长复用并推进 checkpoint，不重复总结已覆盖原文；
- prefix digest、append baseline、retain 策略失效时重建；model view 的消息嵌套列表不可修改；
- manifest 内容增长只刷新动态 artifact index，不重复摘要；压缩后仍能从 manifest 发现旧 handle/digest；
- 空摘要、IOException、超长/缺标题输出的降级路径；最终 candidate 仍执行 hard ceiling；
- summary 输入/输出有界，显式 reasoning 容器会被剥离，异常结果不提交 checkpoint；
- summary prompt 继续包含 Phase 24 的九个结构化标题。

`RequestContextEstimatorTest`：

- system 只计一次；
- user/assistant/tool/contentParts/image/tool name/id/args 均单调计入；
- DeepSeek/Kimi reasoning 计入，GLM/Step/Free 不计历史 reasoning；
- tools 为 null 时 schema=0，动态 MCP schema 变大时估算上升；
- tools wire wrapper 与真实 request builder 对齐；
- response reserve、安全余量、soft/hard 边界；
- effective window=8k、负数/超大配置与 clamp 后 `hardInputCeiling>0`；
- Step 的 body-level `reasoning_format` / `reasoning_effort` 归入 request envelope；
- cached input 不从 window 占用中扣除。

### 8.2 集成测试

| 场景 | 断言 |
|------|------|
| ReAct 连续返回 3 个 100k 工具结果 | 每个都落盘；source/model request/trace/log 不复制旧原文；顺序与 tool call id 不变 |
| Plan task 最终 content 为空 | fallback `allResults` 只包含 envelope，不复制 300k 原文 |
| Plan 多轮、多工具均返回 spill | `ToolResultAccumulator` 总长受控，保留 manifest handle 并标 `partial` |
| Team Worker 读取大结果 | Worker 可以调用共享 store 的 `read_tool_result`；Planner/Reviewer 不误拿工具 schema |
| freshness 预检返回大搜索结果 | 注入 user message 前已 projection，不绕过 spill |
| Kimi 长 reasoning + 大 MCP schema | 完整预算提前触发 model view；provider stub 未收到超 hard ceiling 请求 |
| spill 目录只读/磁盘满 | 返回 bounded failure preview，source history 不出现完整大文本 |
| `/clear` | 主 history、checkpoint 和约定范围内工件被清理；长期记忆不受影响 |

### 8.3 验证命令

```bash
mvn test -Dtest=ToolResultArtifactStoreTest,ConversationHistoryCompactorTest,RequestContextEstimatorTest,ContextProfileTest,MemoryManagerTest,ContextCompressorRequestBudgetTest,ToolRegistryTest -DskipTests=false
mvn test -Dtest=AgentClearHistoryTest,AgentHeadlessResultTest,AgentWebSearchPreflightTest,AgentExplorePrecheckTest,HistoryProjectionAgentIntegrationTest,RequestHardCeilingIntegrationTest,RequestContextVisibilityTest,PlanExecuteAgentTest,SubAgentTest,AgentOrchestratorTest,PlannerTest,RuntimeApiServerTest,AbstractOpenAiCompatibleClientImageInputTest,TracingLlmClientTest -DskipTests=false
mvn test -Pquick
```

手工验证建议再跑一组动态 MCP 大 schema + 大文本输出，观察 `/context` 的 source/model-view/schema/reserve 分项是否与 request log 一致。

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| 工具结果可能包含凭据，落盘扩大本地暴露面 | 为保证逐字恢复，artifact 正文默认不脱敏；用户级隔离目录、支持平台的 owner-only ACL/权限 best-effort、最小元数据、禁止日志正文、TTL/配额、`/clear` 清理，并在 `/context` 明示本地原文存储风险 |
| 工件被删或过期，摘要仍保留 handle | `read_tool_result` 返回明确 `expired/not_found`；摘要仍保留当时 preview 与 digest，不假装可恢复 |
| 写盘失败后重新内联导致爆窗 | 失败只返回 bounded preview + failure marker，绝不回退到完整正文 |
| 工具早已截断，用户误以为句柄包含原始外部响应 | metadata 使用 `captured_complete`，文档明确“相对于 ToolRegistry 边界完整”；保留工具原有 truncated/partial 标记 |
| 读取句柄被用来绕过 PathGuard | handle 不接受路径；严格 session/project scope、正则和 normalize 校验；物理路径永不进入 LLM |
| 本机同用户进程用 symlink 替换 artifact 目录/正文 | 每次读写和递归清理都校验 real parent containment，并拒绝 project/session/body/metadata symlink；损坏 metadata 与长度不一致 fail-closed |
| `read_tool_result` 自己再次 spill | 固定分页上限并设 spill-exempt policy |
| summary checkpoint 与 source 不一致 | covered prefix digest + template/provider version；失效后重建，不修改 source |
| summary 失败后 provider 报超窗 | 请求前 hard ceiling；无法安全构建 view 时受控失败 |
| token 估算不精确 | 真实组成项 + safety margin；日志比较 provider prompt_tokens；不宣称精确 tokenizer |
| 大动态 MCP schema 挤占窗口 | 每轮按实际 tools 集合重算；server/tool 集合变化立即影响 soft trigger |
| prompt cache 因摘要变化而命中下降 | checkpoint 稳定复用，只在 tail 达到阈值时推进；不每轮重写 summary |
| artifact store 被 read-only Explore view 重建成另一个实例 | `readOnlyExploreView()` 显式共享主 store，不按构造器默认值新建 |
| 新工具改变工具数量与 prompt 文案 | ToolRegistry、base prompt、Agent/Plan/SubAgent 说明、AGENTS/README/测试一起联动 |

---

## 10. 完成定义

Phase 90.1–90.3 已满足以下交付条件：

1. 配额内的大工具结果不会完整进入任何 Agent 的模型历史，且句柄分页读取能还原 ToolRegistry 边界文本的 Unicode 规范化值；
2. `ConversationHistoryCompactor` 的测试和代码均不存在对 source history 的 `clear + addAll` 替换；
3. ReAct、Plan、Worker SubAgent 的业务 LLM 调用全部使用 projection view；
4. 压缩与 hard ceiling 判定使用同一个完整请求 accounting，包含实际 tools schema、provider replay reasoning、回复预留和安全余量；
5. `/context`、状态栏与 request log 不再使用彼此冲突的估算口径；
6. spill/summary/句柄读取失败都有显式状态，不静默丢失或偷偷回灌全文；
7. Phase 90 定向测试与 Maven package 通过；`mvn test -Pquick` 已执行并确认没有新增 Phase 90 失败，既有环境基线已记录；README、AGENTS、agents-reference、Phase 12 口径和 `.env.example` 已同步。

PaiCLI 的上下文治理由四层组成：**producer 资源边界、artifact 配额与按需恢复、model view 非破坏性压缩、完整请求 hard ceiling**。后续若要做 Runtime 会话恢复、工件加密、provider tokenizer 或流式落盘，可以在这些契约上继续演进，而不需要再次改写 Agent 主循环。
