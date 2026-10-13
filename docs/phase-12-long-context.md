# 第 12 期开发任务：长上下文工程

> 本期目标是让 PaiCLI 的运行策略随模型上下文窗口变化，而不是继续使用固定 300K token 预算和固定 RAG topK。
>
> 历史说明：本文保留 Phase 12 的交付背景，但 short/balanced/long 三档、long 跳过摘要和按模式选择 RAG topK 的口径已被后续版本移除。当前行为以 Phase 90.1–90.3、`AGENTS.md` 和 `docs/agents-reference.md` 为准。

## 1. 已交付范围

- `LlmClient` 能力声明：
  - `maxContextWindow()`
  - `supportsPromptCaching()`
  - `promptCacheMode()`
- 模型默认能力：
  - GLM-5.1：`200000` window，`glm-prompt-cache`
  - DeepSeek V4：`1000000` window，`automatic-prefix-cache`
- `ContextProfile` 当前不再分档：所有模型按 `maxContextWindow` 使用同一套派生公式；MCP resource index 仅按 window >=32k 判断
- `AgentBudget` 是累计 input/output、停滞与轮数兜底；显式 `-Dpaicli.react.token.budget=...` 才设置累计 token 硬预算，不能替代单次请求窗口检查
- Memory 当前有两条独立压缩路径：short-term memory 压缩，以及从完整 source history 生成不可变 model view 的 conversation projection；长窗口也不会关闭投影
- 代码检索策略：
  - 精确定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查，避免把 RAG 当成代码理解首选路径
  - `search_code` 作为 RAG 语义辅助，不再以 short/balanced/long 模式决定行为
- MCP resources 索引：
  - window >= 32k 时把已知 resources 的 URI / 名称 / 描述 / mimeType 注入 system prompt
  - 不注入 body，需要正文时仍调用 read resource 或用户显式 `@server:protocol://path`
  - ReAct、Plan-and-Execute、Multi-Agent SubAgent 都接入同一索引供应器
- Token 可见化：
  - 输出当前已用 token / 动态预算 / window / cached input / 估算成本
  - `ChatResponse` 增加 `cachedInputTokens`
  - OpenAI-compatible SSE usage 中兼容解析常见 cached token 字段
- `/context` 当前显示完整 estimated request：source/model view、reasoning replay、tools wire schema、framing/envelope、response reserve、safety margin、soft trigger 与 hard input ceiling
- Phase 90.3 单次请求 hard gate：response reserve 默认 8,192（最多 window 25%），safety 默认 `max(2,048, window×1%)`（最多 window 10%），soft trigger 为 hard ceiling 的 90%；超限请求在 provider 前返回 `CONTEXT_VIEW_BUILD_FAILED`
- Phase 12 当时把 Banner 升级到 `v12.0.0`，标语为 `Long-Context Agent CLI`；当前 Banner 版本以 `AGENTS.md` 为准

## 2. 明确不做

- 不新增 Anthropic / Claude provider
- 不实现 Anthropic `cache_control` 块
- 不向 GLM / DeepSeek 请求体注入未确认兼容的私有 cache 字段
- 不把 MCP resource body 自动塞进 system prompt
- 不改变 `pom.xml` 的 Maven 产物版本，Jar 仍是 `paicli-1.0-SNAPSHOT.jar`

## 3. 核心文件

```text
src/main/java/com/paicli/context/
├── ContextProfile.java
├── RequestContextEstimator.java
├── RequestContextBudget.java
├── ContextViewBuildException.java
└── TokenUsageFormatter.java
```

集成点：

- `LlmClient` / `GLMClient` / `DeepSeekClient`：模型能力声明
- `AbstractOpenAiCompatibleClient`：解析 cached input tokens
- `AgentBudget`：累计 usage / 停滞 / 轮数兜底
- `RequestContextEstimator` / `RequestContextBudget`：完整单次请求 accounting、soft trigger 与 hard ceiling
- `MemoryManager` / `TokenBudget` / `ConversationMemory`：short-term memory 与 conversation projection 采用独立预算/策略
- `ToolRegistry`：`glob_files` / `grep_code` / `read_file` 提供实时确定性代码定位，`search_code` 仅作语义辅助
- `McpServerManager`：生成 MCP resources prompt index
- `Agent` / `PlanExecuteAgent` / `AgentOrchestrator` / `SubAgent`：注入长上下文策略与资源索引
- `Main`：Banner、模型切换后的上下文策略提示、Plan/Team resource index 供应器

## 4. 验证

新增或扩展测试：

- `ContextProfileTest`
- `AgentBudgetTest`
- `MemoryManagerTest`
- `McpServerManagerTest`

最终验证命令：

```bash
mvn clean package
```

Phase 12 当时的通过结果：`347 tests, 0 failures, 0 errors, 0 skipped`。
