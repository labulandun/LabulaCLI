package com.paicli.context;

import com.paicli.config.ConfigValueResolver;
import com.paicli.llm.LlmClient;

/**
 * 上下文策略配置。
 *
 * **设计原则**：没有"长 / 短 / 平衡"模式分档。所有参数都是 maxContextWindow 的简单函数，
 * 全模型走同一套行为，只是 window 大小不同导致触发时机和容量不同。
 *
 * 全局常量：
 * - 压缩触发阈值：占用率 ≥ 90% 时触发
 *
 * 按 window 派生：
 * - 短期记忆预算 = window × 0.45
 * - 注入到 system prompt 的相关记忆 token 上限 = window × 0.005，封顶 5000
 * - MCP resource 索引注入：window ≥ 32k 才有意义（再小就挤）
 */
public record ContextProfile(
        int maxContextWindow,
        int agentTokenBudget,
        double compressionTriggerRatio,
        int shortTermMemoryBudget,
        int memoryContextTokens,
        int responseReserveTokens,
        int safetyMarginTokens,
        boolean mcpResourceIndexEnabled,
        boolean promptCachingSupported,
        String promptCacheMode
) {
    public static final double DEFAULT_COMPRESSION_TRIGGER_RATIO = 0.90;
    private static final int MIN_WINDOW = 8_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;
    private static final int DEFAULT_RESPONSE_RESERVE_TOKENS = 8_192;
    private static final int DEFAULT_MIN_SAFETY_MARGIN_TOKENS = 2_048;

    public static ContextProfile from(LlmClient llmClient) {
        int window = Math.max(MIN_WINDOW, llmClient == null ? 128_000 : llmClient.maxContextWindow());
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTermBudget(window),
                memoryContextTokens(window),
                responseReserve(window),
                safetyMargin(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode()
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTerm,
                memoryContextTokens(window),
                responseReserve(window),
                safetyMargin(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    /** Test/embedding hook with explicit reserves; production uses configuration-backed factories. */
    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget,
                                        int responseReserveTokens, int safetyMarginTokens) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        int response = clampReserve(responseReserveTokens, window, 0.25);
        int safety = clampReserve(safetyMarginTokens, window, 0.10);
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTerm,
                memoryContextTokens(window),
                response,
                safety,
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    /** Hard ceiling for input after reserving the next response and estimator safety margin. */
    public int hardInputCeilingTokens() {
        return Math.max(1, maxContextWindow - responseReserveTokens - safetyMarginTokens);
    }

    /** 触发压缩的绝对 token 阈值（完整请求占用 ≥ 此值即压缩） */
    public int compressionTriggerTokens() {
        return Math.max(1, (int) Math.floor(hardInputCeilingTokens() * compressionTriggerRatio));
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 输入硬上限: " + hardInputCeilingTokens()
                + " | 压缩阈值: " + (int) (compressionTriggerRatio * 100) + "% hard ceiling ("
                + compressionTriggerTokens() + " tokens)"
                + " | response reserve: " + responseReserveTokens
                + " | safety: " + safetyMarginTokens
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token 上限（input + output 累计），保 20% 余量给响应突发
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }

    private static int responseReserve(int window) {
        int configured = ConfigValueResolver.nonNegativeInt(
                "paicli.context.response.reserve.tokens",
                "PAICLI_CONTEXT_RESPONSE_RESERVE_TOKENS",
                DEFAULT_RESPONSE_RESERVE_TOKENS);
        return clampReserve(configured, window, 0.25);
    }

    private static int safetyMargin(int window) {
        int defaultValue = Math.max(DEFAULT_MIN_SAFETY_MARGIN_TOKENS,
                (int) Math.floor(window * 0.01));
        int configured = ConfigValueResolver.nonNegativeInt(
                "paicli.context.safety.margin.tokens",
                "PAICLI_CONTEXT_SAFETY_MARGIN_TOKENS",
                defaultValue);
        return clampReserve(configured, window, 0.10);
    }

    private static int clampReserve(int configured, int window, double maxRatio) {
        int nonNegative = Math.max(0, configured);
        int maximum = Math.max(0, (int) Math.floor(window * maxRatio));
        return Math.min(nonNegative, maximum);
    }
}
