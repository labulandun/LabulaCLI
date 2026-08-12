package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmCancelledException;
import com.paicli.llm.LlmTimeoutException;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.context.ContextProfile;
import com.paicli.context.TokenUsageFormatter;
import com.paicli.lsp.LspDiagnosticReport;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.ExplicitMemoryHints;
import com.paicli.memory.HistoryProjectionResult;
import com.paicli.memory.HistoryProjectionState;
import com.paicli.memory.MemoryManager;
import com.paicli.observability.PaiCliTracing;
import com.paicli.prompt.PromptAssembler;
import com.paicli.prompt.PromptContext;
import com.paicli.prompt.PromptMode;
import com.paicli.render.PlainRenderer;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.runtime.CancellationContext;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.skill.SkillIndexFormatter;
import com.paicli.skill.SkillRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    // explore_codebase 派发的子 Agent 各起一个 LLM 子会话；并发上限设得比 MAX_PARALLEL_TOOLS(4) 更小,
    // 避免一轮里多个 explore_codebase 同时拉起多个 LLM 子会话。
    private static final int MAX_PARALLEL_EXPLORERS = 2;
    private static final long EXPLORE_ACQUIRE_TIMEOUT_SECONDS = 30;
    // 子 Agent 探索过程默认折叠 / 静默：中间多轮 grep/read 不进主 transcript,只回传最终报告。
    private static final PrintStream EXPLORER_SILENT_OUT = new PrintStream(OutputStream.nullOutputStream());

    // 23.5 确定性预检：窗口内连续检索工具调用数超此阈值时,注入建议改用 explore_codebase 的提示。
    static final int EXPLORE_PRECHECK_THRESHOLD = 6;
    private static final Set<String> SEARCH_TOOL_NAMES = Set.of(
            "grep_code", "glob_files", "read_file");
    private int consecutiveSearchToolCalls;
    private final PaiCliTracing tracing;
    private final String traceSessionId = UUID.randomUUID().toString();

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private HistoryProjectionState historyProjectionState = HistoryProjectionState.empty();
    private List<LlmClient.Message> lastModelConversationView = List.of();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private final Semaphore explorerSemaphore = new Semaphore(MAX_PARALLEL_EXPLORERS);

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), PaiCliTracing.noop());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, PaiCliTracing.noop());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, PaiCliTracing tracing) {
        this.tracing = tracing == null ? PaiCliTracing.noop() : tracing;
        this.llmClient = this.tracing.traceLlmClient(llmClient);
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(this.llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(this.llmClient);
        this.toolRegistry.setTracing(this.tracing);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(this.llmClient.getProviderName(), this.llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        wireExploreRunner();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    /**
     * 把 explore_codebase 工具接到只读检索子 Agent（Phase 23.3 装配接线）。
     *
     * <p>装配方向合法（agent → tool）：{@code llmClient} 留在 lambda 闭包里,{@code ToolRegistry}
     * 只认 {@link com.paicli.tool.CodeExploreRunner} 接口,不反向 import {@code SubAgent}。
     * lambda 读 {@code this.llmClient} 字段而非捕获构造参数,确保 {@link #setLlmClient} 换模型后仍生效。
     *
     * <p>边界处理：并发上限（{@link #explorerSemaphore}）、子 Agent 失败 / 超 budget 时返回可读文本
     * 而非抛出,主 Agent 可据此回退到自己检索。
     */
    private void wireExploreRunner() {
        toolRegistry.setExploreRunner((task, focusPaths) -> {
            boolean acquired = false;
            try {
                acquired = explorerSemaphore.tryAcquire(EXPLORE_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    return "探索子 Agent 并发已满，请稍后重试或自行用 grep_code / read_file 检索";
                }
                ToolRegistry readOnly = toolRegistry.readOnlyExploreView();
                SubAgent explorer = new SubAgent("explorer", AgentRole.WORKER, llmClient, readOnly);
                String prompt = ExploreContract.buildPrompt(task, focusPaths);
                AgentMessage result = explorer.execute(AgentMessage.task("main", prompt), EXPLORER_SILENT_OUT);
                String report = ExploreContract.clampReport(result.content());
                if (result.type() == AgentMessage.Type.ERROR) {
                    return "探索未完成（可自行检索）：" + report;
                }
                return report;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "探索被中断：" + e.getMessage();
            } catch (Exception e) {
                log.warn("explore_codebase sub-agent failed", e);
                return "探索失败（可自行检索）：" + e.getMessage();
            } finally {
                if (acquired) {
                    explorerSemaphore.release();
                }
            }
        });
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = tracing.traceLlmClient(llmClient);
        this.memoryManager.setLlmClient(this.llmClient);
        this.historyCompactor.setLlmClient(this.llmClient);
        this.historyProjectionState = HistoryProjectionState.empty();
        this.lastModelConversationView = List.of();
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(this.llmClient.getProviderName(), this.llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        return run(userInput, false);
    }

    /** Runtime API/后台任务需要拿到语义结果；流式内容已经渲染时也不能返回空串。 */
    public String runForResult(String userInput) {
        return run(userInput, true);
    }

    private String run(String userInput, boolean returnStreamedContent) {
        try (CancellationContext.RunLease ignored = CancellationContext.openRun();
             PaiCliTracing.AgentRun traceRun = tracing.startAgentRun(
                userInput, llmClient.getProviderName(), llmClient.getModelName(), traceSessionId)) {
            try {
                String result = runTraced(userInput, traceRun, returnStreamedContent);
                if (result != null && !result.isBlank()) {
                    traceRun.outputIfAbsent(result);
                }
                return result;
            } catch (RuntimeException e) {
                traceRun.error(PaiCliTracing.RunOutcome.UNEXPECTED_ERROR, e);
                throw e;
            }
        }
    }

    private String runTraced(String userInput, PaiCliTracing.AgentRun traceRun,
                             boolean returnStreamedContent) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        if (CancellationContext.isCancelled()) {
            boolean timedOut = CancellationContext.isDeadlineExceeded();
            traceRun.outcome(timedOut ? PaiCliTracing.RunOutcome.TIMEOUT : PaiCliTracing.RunOutcome.CANCELLED,
                    timedOut ? "全链路 Deadline 已耗尽" : "任务启动前已取消");
            return CancellationContext.stopMessage("当前任务");
        }
        pruneHistoricalImagePayloads();
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（如有 skill body 注入，前置到原文之前）
        String userMessageContent = prependSkillBodies(userInput);
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        injectFreshnessWebSearchIfNeeded(userInput);
        consecutiveSearchToolCalls = 0;
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        while (true) {
            if (CancellationContext.isCancelled()) {
                boolean timedOut = CancellationContext.isDeadlineExceeded();
                log.info("ReAct run stopped before iteration: timeout={}", timedOut);
                pushStatus(budget, startNanos, "idle");
                traceRun.outcome(timedOut ? PaiCliTracing.RunOutcome.TIMEOUT : PaiCliTracing.RunOutcome.CANCELLED,
                        timedOut ? "全链路 Deadline 已耗尽" : "用户在迭代开始前取消");
                return CancellationContext.stopMessage("当前任务");
            }
            // 先向 source history 注入本轮诊断，再基于 source 构建不可变 model view。
            injectPendingLspDiagnostics();
            HistoryProjectionResult projection = projectHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                traceRun.outcome(PaiCliTracing.RunOutcome.BUDGET_EXHAUSTED, description);
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();
                lastModelConversationView = projection.modelMessages();
                logRequestContext("react iteration=" + iteration, toolDefinitions,
                        lastModelConversationView);
                streamRenderer.beginThinking();
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        lastModelConversationView,
                        toolDefinitions,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    boolean timedOut = CancellationContext.isDeadlineExceeded();
                    log.info("ReAct run stopped after LLM response: timeout={}", timedOut);
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    traceRun.outcome(timedOut ? PaiCliTracing.RunOutcome.TIMEOUT : PaiCliTracing.RunOutcome.CANCELLED,
                            timedOut ? "全链路 Deadline 已耗尽" : "用户在 LLM 响应后取消");
                    return CancellationContext.stopMessage("当前任务");
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                    // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"
                    // 造成标题和内容错位。重置后下一轮迭代的 reasoning/content 会重新打印标题。
                    streamRenderer.resetBetweenIterations();
                    renderer().appendToolCalls(response.toolCalls());

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    appendImageToolMessages(toolResults);
                    maybeInjectExploreSuggestion(response.toolCalls(), toolResults, userInput);
                    pushStatus(budget, startNanos, "running");

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    traceRun.output(response.content());
                    streamRenderer.finish();
                    return returnStreamedContent && response.content() != null ? response.content() : "";
                }
                streamRenderer.clearThinkingPanel();
                String finalResponse = formatUserFacingResponse(reasoningTranscript.toString(), response.content());
                traceRun.output(response.content());
                return finalResponse;

            } catch (LlmTimeoutException e) {
                log.warn("LLM call timed out in ReAct loop: phase={}", e.phase());
                streamRenderer.finish();
                pushStatus(budget, startNanos, "idle");
                traceRun.error(PaiCliTracing.RunOutcome.TIMEOUT, e);
                return "⏱️ LLM 调用超时（" + e.phase() + "）: " + e.getMessage();
            } catch (LlmCancelledException e) {
                log.info("LLM call cancelled in ReAct loop");
                streamRenderer.finish();
                pushStatus(budget, startNanos, "idle");
                traceRun.outcome(PaiCliTracing.RunOutcome.CANCELLED, e.getMessage());
                return "⏹️ 已取消当前任务。";
            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                traceRun.error(PaiCliTracing.RunOutcome.LLM_ERROR, e);
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        resetConversationState(true);
    }

    /** Switches project/artifact scope and drops messages that contain handles from the old scope. */
    public boolean switchProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return false;
        }
        Path previous = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
        Path next = Path.of(projectPath).toAbsolutePath().normalize();
        boolean changed;
        try {
            changed = !Files.isSameFile(previous, next);
        } catch (IOException ignored) {
            changed = !previous.equals(next);
        }
        toolRegistry.setProjectPath(next.toString());
        memoryManager.setProjectPath(next.toString());
        if (changed) {
            // ToolRegistry has already rebound to a fresh artifact session. Do not rotate it twice.
            resetConversationState(false);
        }
        return changed;
    }

    private void resetConversationState(boolean rotateArtifactSession) {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
        historyProjectionState = HistoryProjectionState.empty();
        lastModelConversationView = List.of();
        if (rotateArtifactSession) {
            toolRegistry.clearToolResultArtifacts();
        }

        // 清空短期记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .build());
    }

    private HistoryProjectionResult projectHistory() {
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            HistoryProjectionResult projection = historyCompactor.project(
                    conversationHistory,
                    historyProjectionState,
                    trigger,
                    toolRegistry.getToolResultArtifactIndex());
            if (projection.checkpointAdvanced()) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
            historyProjectionState = projection.nextState();
            return projection;
        } catch (Exception e) {
            log.warn("conversationHistory projection failed; using immutable source snapshot", e);
            List<LlmClient.Message> sourceSnapshot = List.copyOf(conversationHistory);
            int tokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(sourceSnapshot);
            return new HistoryProjectionResult(
                    sourceSnapshot,
                    historyProjectionState,
                    false,
                    false,
                    0,
                    0,
                    "",
                    tokens,
                    tokens,
                    true);
        }
    }

    /**
     * 23.5 确定性预检兜底：工具执行后统计窗口内连续检索工具调用数，
     * 超阈值时注入一条建议，引导 LLM 改用 explore_codebase 派发后续检索。
     * 只"建议"不强行劫持；用户明确要求"自己查/不要派发"时跳过。
     */
    void maybeInjectExploreSuggestion(List<LlmClient.ToolCall> toolCalls,
                                     List<ToolExecutionResult> toolResults,
                                     String userInput) {
        if (!toolRegistry.hasTool("explore_codebase")) {
            return;
        }
        if (userInput != null) {
            String lower = userInput.toLowerCase(Locale.ROOT);
            if (lower.contains("不要派发") || lower.contains("不用派发")
                    || lower.contains("自己查") || lower.contains("自己搜")
                    || lower.contains("不要 explore") || lower.contains("不用 explore")) {
                consecutiveSearchToolCalls = 0;
                return;
            }
        }

        boolean allSearch = toolCalls != null && !toolCalls.isEmpty()
                && toolCalls.stream().allMatch(tc -> SEARCH_TOOL_NAMES.contains(tc.function().name()));
        if (allSearch) {
            consecutiveSearchToolCalls += toolCalls.size();
        } else {
            consecutiveSearchToolCalls = 0;
        }

        boolean hasPartial = toolResults != null && toolResults.stream()
                .anyMatch(ToolExecutionResult::partial);

        if (consecutiveSearchToolCalls >= EXPLORE_PRECHECK_THRESHOLD || hasPartial && consecutiveSearchToolCalls >= 3) {
            String hint = "💡 系统提示：检测到连续 " + consecutiveSearchToolCalls
                    + " 次检索工具调用"
                    + (hasPartial ? "（含 partial 截断结果）" : "")
                    + "，上下文正在快速膨胀。建议改用 `explore_codebase` 工具将后续检索委托给只读子 Agent，"
                    + "只回传精炼结论，避免主上下文被大量中间结果撑满。";
            conversationHistory.add(LlmClient.Message.user(hint));
            renderer().stream().println(AnsiStyle.subtle("  → " + hint));
            log.info("Explore precheck triggered: consecutiveSearchCalls={}, hasPartial={}",
                    consecutiveSearchToolCalls, hasPartial);
            consecutiveSearchToolCalls = 0;
        }
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
    }

    private void injectFreshnessWebSearchIfNeeded(String userInput) {
        if (CancellationContext.isCancelled() || !shouldRunFreshnessWebSearch(userInput)) {
            return;
        }
        String query = buildFreshnessSearchQuery(userInput);
        if (query.isBlank()) {
            return;
        }
        try {
            String args = new ObjectMapper().createObjectNode()
                    .put("query", query)
                    .put("top_k", 5)
                    .toString();
            log.info("Auto freshness web_search preflight: query={}", query);
            renderer().stream().println(AnsiStyle.subtle("  → 检测到时效性问题，已先联网搜索 \"" + query + "\""));
            String callId = "freshness-" + UUID.randomUUID().toString().replace("-", "");
            List<ToolExecutionResult> preflightResults = toolRegistry.executeTools(List.of(
                    new ToolInvocation(callId, "web_search", args)));
            String result = preflightResults.isEmpty() ? "" : preflightResults.get(0).result();
            conversationHistory.add(LlmClient.Message.user("""
                    ## 联网预检结果

                    用户问题包含最新/当前/2026/趋势等时效性需求。以下是本轮进入模型回答前自动执行 `web_search` 得到的结果；回答必须优先基于这些联网结果和用户提供的图片，不要声称无法实时搜索。

                    查询：%s

                    %s
                    """.formatted(query, result == null ? "" : result.trim())));
        } catch (Exception e) {
            if (CancellationContext.isCancelled()) {
                return;
            }
            log.warn("Auto freshness web_search preflight failed", e);
            String failureMessage = e.getMessage();
            if (failureMessage != null
                    && failureMessage.codePointCount(0, failureMessage.length()) > 1_000) {
                failureMessage = failureMessage.substring(
                        0, failureMessage.offsetByCodePoints(0, 1_000));
            }
            String failure = compactOneLine(failureMessage, 240);
            conversationHistory.add(LlmClient.Message.user("""
                    ## 联网预检结果

                    本轮问题需要最新信息，系统已尝试自动联网搜索，但搜索执行失败：%s
                    回答时必须说明这是联网失败后的降级结果，不要声称工具不可用。
                    """.formatted(failure.isBlank() ? "未知错误" : failure)));
        }
    }

    private boolean shouldRunFreshnessWebSearch(String userInput) {
        if (userInput == null || userInput.isBlank() || !toolRegistry.hasTool("web_search")) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("不要联网") || normalized.contains("不用联网")
                || normalized.contains("不要搜索") || normalized.contains("不用搜索")
                || normalized.contains("别搜索") || normalized.contains("offline")) {
            return false;
        }
        return normalized.contains("最新")
                || normalized.contains("当前")
                || normalized.contains("今天")
                || normalized.contains("今年")
                || normalized.contains("现在")
                || normalized.contains("新闻")
                || normalized.contains("趋势")
                || normalized.contains("版本")
                || normalized.contains("2026")
                || normalized.contains("latest")
                || normalized.contains("current")
                || normalized.contains("recent");
    }

    private String buildFreshnessSearchQuery(String userInput) {
        String cleaned = userInput == null ? "" : userInput
                .replaceAll("@image:(<[^>]+>|\\S+)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("ai agent") && (cleaned.contains("2026") || cleaned.contains("技术栈") || cleaned.contains("趋势"))) {
            return "2026 AI Agent 技术栈 趋势 MCP A2A Agents SDK LangGraph CrewAI AutoGen";
        }
        if (cleaned.length() > 160) {
            return cleaned.substring(0, 160);
        }
        return cleaned;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /** Last immutable message list actually handed to the business LLM call. */
    public List<LlmClient.Message> getLastModelConversationView() {
        return List.copyOf(lastModelConversationView);
    }

    /** Compatibility alias for tests and diagnostics that do not need the "last" qualifier. */
    public List<LlmClient.Message> getModelConversationView() {
        return getLastModelConversationView();
    }

    public HistoryProjectionState getHistoryProjectionState() {
        return historyProjectionState;
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getContextStatus() {
        com.paicli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        List<LlmClient.Message> contextView = currentEstimatedModelView(profile);
        // 分类估算下一轮可用 model-view candidate；完整 request accounting 留给 Phase 90.3。
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : contextView) {
            int t = com.paicli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("  Tool artifacts: ")
                .append(toolRegistry.getToolResultArtifactCount())
                .append(" files / ")
                .append(formatArtifactBytes(toolRegistry.getToolResultArtifactBytes()))
                .append(" / manifest ")
                .append(toolRegistry.getToolResultManifestHandle())
                .append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private static String formatArtifactBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.paicli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(
                currentEstimatedModelView(memoryManager.getContextProfile()));
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private List<LlmClient.Message> currentEstimatedModelView(ContextProfile profile) {
        try {
            return historyCompactor.previewModelMessages(
                    conversationHistory,
                    historyProjectionState,
                    profile.compressionTriggerTokens(),
                    toolRegistry.getToolResultArtifactIndex());
        } catch (RuntimeException e) {
            log.debug("failed to build model-view context preview; using immutable source snapshot", e);
            return List.copyOf(conversationHistory);
        }
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools,
                                   List<LlmClient.Message> requestMessages) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        List<LlmClient.Message> messagesToLog = requestMessages == null ? List.of() : requestMessages;
        for (int messageIndex = 0; messageIndex < messagesToLog.size(); messageIndex++) {
            LlmClient.Message msg = messagesToLog.get(messageIndex);
            messages++;
            int tokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.paicli.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result [{}]: {}", result.name(), preview(result.observabilitySummary(), 500));
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        if (result.artifactWriteFailed()) {
            renderer().stream().println(AnsiStyle.subtle(
                    "  → 工具结果落盘失败，仅保留有界预览（" + result.name() + "）"));
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolExecutionResult result) {
        String text = result.displayPreview() == null ? "" : result.displayPreview();
        boolean stepSearch = result.resultMetadata().stepSearch();
        if (result.resultMetadata().failed() || text.contains("未找到相关结果")) {
            return compactOneLine(result.resultMetadata().firstLine(), 120);
        }
        long count = result.resultMetadata().resultCount();
        if (count == 0) {
            count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        }
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolExecutionResult result) {
        String text = result.displayPreview() == null ? "" : result.displayPreview();
        boolean stepSearch = result.resultMetadata().stepSearch();
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (result.resultMetadata().failed()) {
            return verb + target + " 失败: " + compactOneLine(result.resultMetadata().firstLine(), 100);
        }
        String title = result.resultMetadata().title();
        if (title.isBlank()) {
            title = text.lines()
                    .filter(line -> line.startsWith("📄 标题:"))
                    .map(line -> line.substring("📄 标题:".length()).trim())
                    .findFirst()
                    .orElse("");
        }
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversationHistory.add(LlmClient.Message.user(parts));
        }
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking("Thinking..."));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
