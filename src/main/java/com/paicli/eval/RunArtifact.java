package com.paicli.eval;

import java.util.List;

/**
 * 一次 case 运行的聚合工件：状态 + 回答 + trace 关联 + 工作区变更统计 +
 * 从 spans.jsonl 解析出的 LLM/工具明细（见 docs/evaluation-system-design.md §3.3）。
 */
public record RunArtifact(String caseId,
                          int runIndex,
                          String status,
                          String finalAnswer,
                          String error,
                          String traceId,
                          String rootObservationId,
                          long wallTimeMs,
                          int filesChanged,
                          int insertions,
                          int deletions,
                          LlmStats llm,
                          List<ToolCallStat> tools) {

    /** token 口径与 Langfuse usage_details 对齐：input 不含 cached，total 用供应商返回值累加。 */
    public record LlmStats(int rounds,
                           long inputTokens,
                           long cachedTokens,
                           long outputTokens,
                           long totalTokens) {
        public static final LlmStats EMPTY = new LlmStats(0, 0, 0, 0, 0);
    }

    public record ToolCallStat(String name, String status, long durationMs) {

        public boolean isError() {
            return "ERROR".equals(status);
        }
    }

    public long toolErrors() {
        return tools.stream().filter(ToolCallStat::isError).count();
    }
}
