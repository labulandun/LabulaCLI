package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 输出 run 级 report.json（机器可 diff）与 report.md（人读摘要）。 */
public final class Reporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Reporter() {
    }

    public static void write(Path runDir, Map<String, String> runMeta, List<CaseResult> results) throws IOException {
        Files.createDirectories(runDir);
        ObjectNode report = buildJson(runMeta, results);
        Files.writeString(runDir.resolve("report.json"), MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("report.md"), buildMarkdown(runMeta, results), StandardCharsets.UTF_8);
    }

    static ObjectNode buildJson(Map<String, String> runMeta, List<CaseResult> results) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode run = root.putObject("run");
        runMeta.forEach(run::put);

        List<CaseResult> executed = results.stream().filter(CaseResult::executed).toList();
        long passed = executed.stream().filter(CaseResult::taskSuccess).count();
        ObjectNode summary = root.putObject("summary");
        summary.put("total", results.size());
        summary.put("executed", executed.size());
        summary.put("skipped", results.size() - executed.size());
        summary.put("passed", passed);
        summary.put("pass_rate", executed.isEmpty() ? 0.0 : round4((double) passed / executed.size()));
        summary.put("avg_wall_time_ms", executed.isEmpty() ? 0
                : (long) executed.stream().mapToLong(r -> r.artifact().wallTimeMs()).average().orElse(0));
        summary.put("tokens_total", executed.stream()
                .mapToLong(r -> r.artifact().llm().totalTokens()).sum());
        long toolCalls = executed.stream().mapToLong(r -> r.artifact().tools().size()).sum();
        long toolErrors = executed.stream().mapToLong(r -> r.artifact().toolErrors()).sum();
        summary.put("tool_calls", toolCalls);
        summary.put("tool_errors", toolErrors);
        summary.put("tool_error_rate", toolCalls == 0 ? 0.0 : round4((double) toolErrors / toolCalls));

        ObjectNode suites = summary.putObject("suites");
        for (Map.Entry<String, List<CaseResult>> entry : groupBySuite(executed).entrySet()) {
            long suitePassed = entry.getValue().stream().filter(CaseResult::taskSuccess).count();
            ObjectNode suiteNode = suites.putObject(entry.getKey());
            suiteNode.put("executed", entry.getValue().size());
            suiteNode.put("passed", suitePassed);
            suiteNode.put("pass_rate", round4((double) suitePassed / entry.getValue().size()));
        }

        ArrayNode cases = root.putArray("cases");
        for (CaseResult result : results) {
            ObjectNode node = cases.addObject();
            node.put("id", result.caseId());
            node.put("suite", result.suite());
            node.put("run_index", result.runIndex());
            node.put("status", result.artifact().status());
            node.put("task_success", result.taskSuccess());
            node.put("wall_time_ms", result.artifact().wallTimeMs());
            node.put("files_changed", result.artifact().filesChanged());
            node.put("insertions", result.artifact().insertions());
            node.put("deletions", result.artifact().deletions());
            node.put("llm_rounds", result.artifact().llm().rounds());
            node.put("tokens_total", result.artifact().llm().totalTokens());
            node.put("tool_calls", result.artifact().tools().size());
            node.put("tool_errors", result.artifact().toolErrors());
            if (result.artifact().traceId() != null) {
                node.put("trace_id", result.artifact().traceId());
            }
            if (result.skipReason() != null) {
                node.put("skip_reason", result.skipReason());
            }
            ArrayNode scores = node.putArray("scores");
            for (Score score : result.scores()) {
                ObjectNode scoreNode = scores.addObject();
                scoreNode.put("name", score.name());
                scoreNode.put("value", score.value());
                scoreNode.put("data_type", score.dataType().name());
                scoreNode.put("comment", score.comment());
            }
        }
        return root;
    }

    static String buildMarkdown(Map<String, String> runMeta, List<CaseResult> results) {
        List<CaseResult> executed = results.stream().filter(CaseResult::executed).toList();
        long passed = executed.stream().filter(CaseResult::taskSuccess).count();
        StringBuilder md = new StringBuilder();
        md.append("# PaiCLI Eval Report\n\n");
        runMeta.forEach((key, value) -> md.append("- ").append(key).append(": ").append(value).append('\n'));
        md.append('\n');
        md.append(String.format("**pass_rate: %d/%d**", passed, executed.size()));
        if (results.size() > executed.size()) {
            md.append(String.format("（另有 %d 条 skipped）", results.size() - executed.size()));
        }
        md.append("\n\n");
        md.append("| case | status | task_success | wall_time | tokens | rounds | tools | files± | scores |\n");
        md.append("|------|--------|--------------|-----------|--------|--------|-------|--------|--------|\n");
        for (CaseResult result : results) {
            md.append(String.format("| %s | %s | %s | %.1fs | %d | %d | %d(%dE) | %d(+%d/-%d) | %s |\n",
                    result.caseId(),
                    result.artifact().status(),
                    result.executed() ? (result.taskSuccess() ? "✅" : "❌") : "⏭",
                    result.artifact().wallTimeMs() / 1000.0,
                    result.artifact().llm().totalTokens(),
                    result.artifact().llm().rounds(),
                    result.artifact().tools().size(),
                    result.artifact().toolErrors(),
                    result.artifact().filesChanged(),
                    result.artifact().insertions(),
                    result.artifact().deletions(),
                    scoreSummary(result)));
        }
        List<CaseResult> failed = executed.stream().filter(r -> !r.taskSuccess()).toList();
        if (!failed.isEmpty()) {
            md.append("\n## 失败明细\n");
            for (CaseResult result : failed) {
                md.append("\n### ").append(result.caseId()).append("\n\n");
                if (result.artifact().error() != null && !result.artifact().error().isBlank()) {
                    md.append("- 运行错误: ").append(result.artifact().error()).append('\n');
                }
                for (Score score : result.scores()) {
                    if (!score.passed() && score.dataType() == Score.DataType.BOOLEAN) {
                        md.append("- ").append(score.name()).append(": ")
                                .append(firstLines(score.comment(), 6)).append('\n');
                    }
                }
            }
        }
        return md.toString();
    }

    /** md 表格只列 BOOLEAN 断言分；NUMERIC 指标已有独立列（report.json 里两者都全）。 */
    private static String scoreSummary(CaseResult result) {
        StringBuilder sb = new StringBuilder();
        for (Score score : result.scores()) {
            if (score.dataType() != Score.DataType.BOOLEAN) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(score.name()).append('=').append(score.passed() ? "1" : "0");
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static Map<String, List<CaseResult>> groupBySuite(List<CaseResult> executed) {
        Map<String, List<CaseResult>> grouped = new LinkedHashMap<>();
        for (CaseResult result : executed) {
            grouped.computeIfAbsent(result.suite(), k -> new java.util.ArrayList<>()).add(result);
        }
        return grouped;
    }

    private static String firstLines(String text, int maxLines) {
        String[] lines = text == null ? new String[0] : text.split("\n");
        int limit = Math.min(lines.length, maxLines);
        return String.join(" / ", java.util.Arrays.copyOfRange(lines, 0, limit));
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
