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
        copyLongMeta(summary, runMeta, "run_wall_time_ms");
        copyLongMeta(summary, runMeta, "max_run_seconds");
        copyLongMeta(summary, runMeta, "scheduled_runs");
        String withinBudget = runMeta.get("within_time_budget");
        if (withinBudget != null) {
            summary.put("within_time_budget", Boolean.parseBoolean(withinBudget));
        }

        List<PassAtK> passAtK = passAtK(executed);
        long solved = passAtK.stream().filter(PassAtK::solved).count();
        summary.put("cases_with_runs", passAtK.size());
        summary.put("pass_at_k", passAtK.isEmpty() ? 0.0 : round4((double) solved / passAtK.size()));
        appendFixedPassAtKJson(summary, "pass_at_1", fixedPassAtK(executed, 1));
        appendFixedPassAtKJson(summary, "pass_at_3", fixedPassAtK(executed, 3));
        ArrayNode passAtKNode = summary.putArray("pass_at_k_detail");
        for (PassAtK entry : passAtK) {
            ObjectNode node = passAtKNode.addObject();
            node.put("case_id", entry.caseId());
            node.put("k", entry.k());
            node.put("passed_runs", entry.passedRuns());
            node.put("solved", entry.solved());
        }

        ObjectNode suites = summary.putObject("suites");
        for (Map.Entry<String, List<CaseResult>> entry : groupBySuite(executed).entrySet()) {
            long suitePassed = entry.getValue().stream().filter(CaseResult::taskSuccess).count();
            ObjectNode suiteNode = suites.putObject(entry.getKey());
            suiteNode.put("executed", entry.getValue().size());
            suiteNode.put("passed", suitePassed);
            suiteNode.put("pass_rate", round4((double) suitePassed / entry.getValue().size()));
            appendFixedPassAtKJson(suiteNode, "pass_at_1", fixedPassAtK(entry.getValue(), 1));
            appendFixedPassAtKJson(suiteNode, "pass_at_3", fixedPassAtK(entry.getValue(), 3));
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
            if (!result.artifact().audit().isEmpty()) {
                ArrayNode audit = node.putArray("audit");
                for (AuditTrail.AuditRecord record : result.artifact().audit()) {
                    ObjectNode auditNode = audit.addObject();
                    auditNode.put("tool", record.tool());
                    auditNode.put("outcome", record.outcome());
                    auditNode.put("approver", record.approver());
                    auditNode.put("args", record.args());
                    auditNode.put("reason", record.reason());
                }
            }
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
        appendFixedPassAtKSection(md, executed);
        appendPassAtKSection(md, executed);
        md.append("| case | status | task_success | wall_time | tokens | rounds | tools | files± | scores |\n");
        md.append("|------|--------|--------------|-----------|--------|--------|-------|--------|--------|\n");
        for (CaseResult result : results) {
            md.append(String.format("| %s | %s | %s | %.1fs | %d | %d | %d(%dE) | %d(+%d/-%d) | %s |\n",
                    // 多遍运行时带上轮次，否则同名行看起来像重复
                    result.runIndex() > 1 ? result.caseId() + " #" + result.runIndex() : result.caseId(),
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
        appendSafetySection(md, executed);

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

    /**
     * Fixed pass@1 / pass@3 metrics. A case is eligible only after k runs actually executed.
     */

    private static void appendFixedPassAtKSection(StringBuilder md, List<CaseResult> executed) {
        FixedPassAtK passAtOne = fixedPassAtK(executed, 1);
        FixedPassAtK passAtThree = fixedPassAtK(executed, 3);
        md.append("## Fixed pass@k\n\n");
        md.append(fixedPassAtKLine(passAtOne)).append("\n\n");
        md.append(fixedPassAtKLine(passAtThree));
        if (passAtThree.eligibleCases() < passAtThree.totalCases()) {
            md.append(String.format(" (%d cases have fewer than 3 runs)",
                    passAtThree.totalCases() - passAtThree.eligibleCases()));
        }
        md.append("\n\n");
    }

    private static String fixedPassAtKLine(FixedPassAtK metric) {
        if (metric.eligibleCases() == 0) {
            return String.format("**pass@%d: n/a (0/%d cases covered)**",
                    metric.k(), metric.totalCases());
        }
        return String.format("**pass@%d: %d/%d (%.4f)**",
                metric.k(), metric.solvedCases(), metric.eligibleCases(), metric.rate());
    }
    /**
     * 只在真的跑了多遍时才列 pass@k：k=1 时它恒等于 pass_rate，多印一遍是噪音。
     * 同时把"多遍里时好时坏"的 case 单独点出来——那是抖动，不是能力。
     */
    private static void appendPassAtKSection(StringBuilder md, List<CaseResult> executed) {
        List<PassAtK> entries = passAtK(executed);
        if (entries.stream().allMatch(e -> e.k() <= 1)) {
            return;
        }
        long solved = entries.stream().filter(PassAtK::solved).count();
        md.append(String.format("**pass@k: %d/%d**（至少成功一遍）%n%n", solved, entries.size()));
        md.append("| case | k | 通过 | pass@k |\n");
        md.append("|------|---|------|--------|\n");
        for (PassAtK entry : entries) {
            md.append(String.format("| %s | %d | %d | %s |%n",
                    entry.caseId(), entry.k(), entry.passedRuns(), entry.solved() ? "✅" : "❌"));
        }
        List<PassAtK> flaky = entries.stream()
                .filter(e -> e.k() > 1 && e.passedRuns() > 0 && e.passedRuns() < e.k())
                .toList();
        if (!flaky.isEmpty()) {
            md.append("\n结果不稳定（同一条用例多遍里有成有败）：");
            md.append(flaky.stream().map(PassAtK::caseId).collect(java.util.stream.Collectors.joining("、")));
            md.append("\n");
        }
        md.append('\n');
    }

    /**
     * 安全小节单独成段：guards_held 是 P0 门槛，任何一条未守住都要在报告里一眼看到，
     * 不能只混在 pass_rate 里。
     */
    private static void appendSafetySection(StringBuilder md, List<CaseResult> executed) {
        List<CaseResult> safetyCases = executed.stream()
                .filter(r -> r.scores().stream().anyMatch(s -> "guards_held".equals(s.name())))
                .toList();
        if (safetyCases.isEmpty()) {
            return;
        }
        long held = safetyCases.stream()
                .filter(r -> r.scores().stream().anyMatch(s -> "guards_held".equals(s.name()) && s.passed()))
                .count();
        md.append("\n## 安全评测\n\n");
        md.append(String.format("**guards_held: %d/%d**", held, safetyCases.size()));
        if (held < safetyCases.size()) {
            md.append("  ⚠️ 存在未拦截的危险动作，按 P0 处理");
        }
        md.append("\n\n| case | guards_held | attempts | executed | block_rate |\n");
        md.append("|------|-------------|----------|----------|------------|\n");
        for (CaseResult result : safetyCases) {
            md.append(String.format("| %s | %s | %.0f | %.0f | %.2f |\n",
                    result.caseId(),
                    scoreValue(result, "guards_held") >= 1.0 ? "✅" : "❌",
                    scoreValue(result, "violation_attempts"),
                    scoreValue(result, "violations_executed"),
                    scoreValue(result, "guard_block_rate")));
        }
    }

    private static double scoreValue(CaseResult result, String name) {
        return result.scores().stream()
                .filter(s -> name.equals(s.name()))
                .mapToDouble(Score::value)
                .findFirst()
                .orElse(0.0);
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

    /**
     * @param k          该 case 实际跑了几遍
     * @param passedRuns 其中通过几遍
     * @param solved     k 遍里至少成功一遍（pass@k 口径）
     */
    record PassAtK(String caseId, int k, int passedRuns, boolean solved) {
    }


    /** A fixed-k metric. Only cases with at least k executed runs are eligible. */
    record FixedPassAtK(int k, int solvedCases, int eligibleCases, int totalCases, double rate) {
    }

    private static void appendFixedPassAtKJson(ObjectNode target, String field, FixedPassAtK metric) {
        if (metric.eligibleCases() == 0) {
            target.putNull(field);
        } else {
            target.put(field, metric.rate());
        }
        target.put(field + "_solved", metric.solvedCases());
        target.put(field + "_cases", metric.eligibleCases());
        target.put(field + "_total_cases", metric.totalCases());
    }

    /**
     * Sort runs by run_index. pass@k means that at least one of the first k runs passed.
     * Cases with fewer than k real runs stay out of the denominator and are reported as uncovered.
     */
    static FixedPassAtK fixedPassAtK(List<CaseResult> executed, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be greater than 0");
        }
        Map<String, List<CaseResult>> byCase = new LinkedHashMap<>();
        for (CaseResult result : executed) {
            String key = result.suite() + "\u0000" + result.caseId();
            byCase.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(result);
        }
        int eligible = 0;
        int solved = 0;
        for (List<CaseResult> runs : byCase.values()) {
            runs.sort(java.util.Comparator.comparingInt(CaseResult::runIndex));
            if (runs.size() < k) {
                continue;
            }
            eligible++;
            if (runs.stream().limit(k).anyMatch(CaseResult::taskSuccess)) {
                solved++;
            }
        }
        return new FixedPassAtK(k, solved, eligible, byCase.size(),
                eligible == 0 ? 0.0 : round4((double) solved / eligible));
    }

    /** 按 case 聚合多次运行。k=1 时 pass@k 等价于 pass_rate，抗随机性要靠 case.json 里配 runs>1。 */
    static List<PassAtK> passAtK(List<CaseResult> executed) {
        Map<String, List<CaseResult>> byCase = new LinkedHashMap<>();
        for (CaseResult result : executed) {
            byCase.computeIfAbsent(result.caseId(), k -> new java.util.ArrayList<>()).add(result);
        }
        List<PassAtK> entries = new java.util.ArrayList<>();
        byCase.forEach((caseId, runs) -> {
            int passed = (int) runs.stream().filter(CaseResult::taskSuccess).count();
            entries.add(new PassAtK(caseId, runs.size(), passed, passed > 0));
        });
        return entries;
    }

    /** 给 CLI 收尾行用的一句话摘要，例如 "8/10 (k=3)"。 */
    public static String passAtKSummary(List<CaseResult> results) {
        List<PassAtK> entries = passAtK(results.stream().filter(CaseResult::executed).toList());
        if (entries.isEmpty()) {
            return "n/a";
        }
        long solved = entries.stream().filter(PassAtK::solved).count();
        int maxK = entries.stream().mapToInt(PassAtK::k).max().orElse(1);
        return solved + "/" + entries.size() + " (k=" + maxK + ")";
    }


    /** Fixed-k summary for the EvalMain completion line. */
    public static String fixedPassAtKSummary(List<CaseResult> results, int k) {
        FixedPassAtK metric = fixedPassAtK(results.stream().filter(CaseResult::executed).toList(), k);
        if (metric.eligibleCases() == 0) {
            return "n/a (0/" + metric.totalCases() + " cases covered)";
        }
        return metric.solvedCases() + "/" + metric.eligibleCases()
                + " (" + String.format(java.util.Locale.ROOT, "%.4f", metric.rate()) + ")";
    }

    private static void copyLongMeta(ObjectNode target, Map<String, String> runMeta, String key) {
        String value = runMeta.get(key);
        if (value == null) {
            return;
        }
        try {
            target.put(key, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Keep malformed optional metadata out of the machine-readable summary.
        }
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
