package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanArtifactsTest {

    @TempDir
    Path tempDir;

    private static String spanLine(String name, String type, String status, String level,
                                   long durationMs, String usageJson) {
        String levelAttr = level == null ? ""
                : ",\"langfuse.observation.level\":\"" + level + "\"";
        String usageAttr = usageJson == null ? ""
                : ",\"" + SpanArtifacts.USAGE_ATTR + "\":\"" + usageJson.replace("\"", "\\\"") + "\"";
        return "{\"trace_id\":\"t1\",\"span_id\":\"s-" + name + "\",\"name\":\"" + name + "\","
                + "\"status\":\"" + status + "\",\"duration_ms\":" + durationMs + ","
                + "\"attributes\":{\"langfuse.observation.type\":\"" + type + "\"" + levelAttr + usageAttr + "}}";
    }

    private Path writeSpans(String... lines) throws Exception {
        Path file = tempDir.resolve("spans.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aggregatesLlmUsageAcrossGenerations() throws Exception {
        Path file = writeSpans(
                spanLine("paicli.react.run", "agent", "UNSET", null, 9000, null),
                spanLine("paicli.llm.chat", "generation", "UNSET", null, 3000,
                        "{\"input\":100,\"input_cached_tokens\":50,\"output\":20,\"total\":170}"),
                spanLine("paicli.llm.chat", "generation", "UNSET", null, 2000,
                        "{\"input\":200,\"output\":30,\"total\":230}"));
        List<SpanLog.SpanRecord> spans = SpanLog.read(file);
        RunArtifact.LlmStats llm = SpanArtifacts.llmStats(spans);
        assertEquals(2, llm.rounds());
        assertEquals(300, llm.inputTokens());
        assertEquals(50, llm.cachedTokens());
        assertEquals(50, llm.outputTokens());
        assertEquals(400, llm.totalTokens());
        assertEquals("t1", SpanArtifacts.rootTraceId(spans));
    }

    @Test
    void toolStatsClassifyErrorAndWarning() throws Exception {
        Path file = writeSpans(
                spanLine("grep_code", "tool", "UNSET", null, 120, null),
                spanLine("execute_command", "tool", "ERROR", "ERROR", 500, null),
                spanLine("write_file", "tool", "UNSET", "WARNING", 80, null));
        List<RunArtifact.ToolCallStat> tools = SpanArtifacts.toolStats(SpanLog.read(file));
        assertEquals(3, tools.size());
        assertEquals("OK", tools.get(0).status());
        assertEquals("ERROR", tools.get(1).status());
        assertEquals("WARNING", tools.get(2).status());
    }

    @Test
    void metricScoresCoverTokensRoundsAndToolErrorRate() throws Exception {
        Path file = writeSpans(
                spanLine("paicli.llm.chat", "generation", "UNSET", null, 3000,
                        "{\"input\":100,\"output\":20,\"total\":120}"),
                spanLine("grep_code", "tool", "UNSET", null, 120, null),
                spanLine("execute_command", "tool", "ERROR", "ERROR", 500, null));
        List<SpanLog.SpanRecord> spans = SpanLog.read(file);
        RunArtifact artifact = new RunArtifact("c1", 1, "completed", "", null, "t1", null,
                5000, 0, 0, 0, SpanArtifacts.llmStats(spans), SpanArtifacts.toolStats(spans), List.of());

        Map<String, Double> byName = new java.util.HashMap<>();
        for (Score score : SpanArtifacts.metricScores(artifact)) {
            assertEquals(Score.DataType.NUMERIC, score.dataType());
            byName.put(score.name(), score.value());
        }
        assertEquals(120.0, byName.get("tokens_total"));
        assertEquals(1.0, byName.get("llm_rounds"));
        assertEquals(2.0, byName.get("tool_calls"));
        assertEquals(1.0, byName.get("tool_errors"));
        assertEquals(0.5, byName.get("tool_error_rate"));
    }

    @Test
    void emptyOrMissingSpansProduceNoMetrics() throws Exception {
        assertTrue(SpanLog.read(tempDir.resolve("missing.jsonl")).isEmpty());
        assertTrue(SpanArtifacts.metricScores(EvalFixtures.artifact("c1")).isEmpty());
    }

    @Test
    void truncatedTailLineIsSkipped() throws Exception {
        Path file = tempDir.resolve("spans.jsonl");
        Files.writeString(file,
                spanLine("grep_code", "tool", "UNSET", null, 120, null) + "\n{\"trace_id\":\"t1\",\"na",
                StandardCharsets.UTF_8);
        List<SpanLog.SpanRecord> spans = SpanLog.read(file);
        assertEquals(1, spans.size());
        assertEquals(Optional.of("grep_code"), spans.stream().map(SpanLog.SpanRecord::name).findFirst());
    }
}
