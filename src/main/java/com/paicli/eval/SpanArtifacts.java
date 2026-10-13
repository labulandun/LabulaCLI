package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** spans.jsonl → RunArtifact 的 LLM/工具聚合与自动指标分（Phase E2）。 */
public final class SpanArtifacts {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String USAGE_ATTR = "langfuse.observation.usage_details";

    private SpanArtifacts() {
    }

    public static RunArtifact.LlmStats llmStats(List<SpanLog.SpanRecord> spans) {
        int rounds = 0;
        long input = 0;
        long cached = 0;
        long output = 0;
        long total = 0;
        for (SpanLog.SpanRecord span : spans) {
            if (!"generation".equals(span.type())) {
                continue;
            }
            rounds++;
            JsonNode usage = parseUsage(span.attr(USAGE_ATTR));
            input += usage.path("input").asLong(0);
            cached += usage.path("input_cached_tokens").asLong(0);
            output += usage.path("output").asLong(0);
            total += usage.path("total").asLong(0);
        }
        return new RunArtifact.LlmStats(rounds, input, cached, output, total);
    }

    public static List<RunArtifact.ToolCallStat> toolStats(List<SpanLog.SpanRecord> spans) {
        List<RunArtifact.ToolCallStat> tools = new ArrayList<>();
        for (SpanLog.SpanRecord span : spans) {
            if (!"tool".equals(span.type())) {
                continue;
            }
            tools.add(new RunArtifact.ToolCallStat(span.name(),
                    span.isError() ? "ERROR" : ("WARNING".equals(span.level()) ? "WARNING" : "OK"),
                    span.durationMs()));
        }
        return tools;
    }

    /** 根 agent span 的 traceId；Runtime API 事件缺 trace 字段时的兜底来源。 */
    public static String rootTraceId(List<SpanLog.SpanRecord> spans) {
        return spans.stream()
                .filter(span -> "agent".equals(span.type()))
                .map(SpanLog.SpanRecord::traceId)
                .findFirst()
                .orElse(null);
    }

    /**
     * 每条执行过的 case 自动附带的 NUMERIC 指标分（不参与 task_success）。
     * spans 为空（OTel 未生效）时不产出，避免把 0 误读成"没消耗"。
     */
    public static List<Score> metricScores(RunArtifact artifact) {
        RunArtifact.LlmStats llm = artifact.llm();
        if (llm == null || (llm.rounds() == 0 && artifact.tools().isEmpty())) {
            return List.of();
        }
        List<Score> scores = new ArrayList<>();
        scores.add(numeric("tokens_total", llm.totalTokens(),
                "input=" + llm.inputTokens() + " cached=" + llm.cachedTokens() + " output=" + llm.outputTokens()));
        scores.add(numeric("llm_rounds", llm.rounds(), "generation span 数"));
        scores.add(numeric("tool_calls", artifact.tools().size(), toolBreakdown(artifact)));
        long errors = artifact.toolErrors();
        scores.add(numeric("tool_errors", errors, "ERROR 状态 tool span 数"));
        double errorRate = artifact.tools().isEmpty() ? 0.0 : (double) errors / artifact.tools().size();
        scores.add(numeric("tool_error_rate", Math.round(errorRate * 10000.0) / 10000.0, "tool_errors / tool_calls"));
        return scores;
    }

    private static String toolBreakdown(RunArtifact artifact) {
        StringBuilder sb = new StringBuilder();
        artifact.tools().stream()
                .collect(java.util.stream.Collectors.groupingBy(RunArtifact.ToolCallStat::name,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .forEach((name, count) -> {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(name).append("x").append(count);
                });
        return sb.toString();
    }

    private static Score numeric(String name, double value, String comment) {
        return new Score(name, value, Score.DataType.NUMERIC, comment);
    }

    private static JsonNode parseUsage(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
