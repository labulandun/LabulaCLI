package com.paicli.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Compares a candidate Reporter report.json against an accepted baseline report.json. */
public final class EvalCompare {

    public static final int COMPARE_SCHEMA_VERSION = 1;
    private static final double EPSILON = 1e-12;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalCompare() {
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("help")) {
                printUsage(out);
                return 0;
            }
            Path baseline = requiredPath(options, "baseline");
            Path candidate = requiredPath(options, "candidate");
            ComparePolicy policy = options.containsKey("policy")
                    ? ComparePolicy.load(Path.of(options.get("policy")))
                    : ComparePolicy.defaults();
            String suite = normalizeSuite(options.get("suite"));

            ComparisonResult result = compareFiles(baseline, candidate, policy, suite);
            if (options.containsKey("out")) {
                write(Path.of(options.get("out")), result);
            }
            out.print(buildMarkdown(result));
            return result.passed() ? 0 : 1;
        } catch (CompareInputException | IllegalArgumentException | IOException e) {
            err.println("EvalCompare input error: " + e.getMessage());
            return 2;
        }
    }

    public static ComparisonResult compareFiles(Path baselineFile,
                                                Path candidateFile,
                                                ComparePolicy policy,
                                                String suite)
            throws IOException, CompareInputException {
        JsonNode baseline = readReport(baselineFile, "baseline");
        JsonNode candidate = readReport(candidateFile, "candidate");
        return compare(baseline, candidate, policy, normalizeSuite(suite));
    }

    static ComparisonResult compare(JsonNode baseline,
                                    JsonNode candidate,
                                    ComparePolicy policy,
                                    String suite) throws CompareInputException {
        policy.validate();
        validateReport(baseline, "baseline");
        validateReport(candidate, "candidate");

        Map<RunKey, CaseView> baselineCases = cases(baseline, suite, "baseline");
        Map<RunKey, CaseView> candidateCases = cases(candidate, suite, "candidate");
        List<Check> checks = new ArrayList<>();

        if (policy.requireCaseSetMatch()) {
            if (!baselineCases.keySet().equals(candidateCases.keySet())) {
                throw new CompareInputException(caseSetMismatch(baselineCases.keySet(), candidateCases.keySet()));
            }
            checks.add(Check.pass("case_set", (double) baselineCases.size(),
                    (double) candidateCases.size(), "exact match", "case/run coverage matches"));
        } else {
            checks.add(Check.skip("case_set", "case-set matching disabled by policy"));
        }

        long baselineExecuted = baselineCases.values().stream().filter(CaseView::executed).count();
        long candidateExecuted = candidateCases.values().stream().filter(CaseView::executed).count();
        List<RunKey> newlySkipped = baselineCases.entrySet().stream()
                .filter(e -> e.getValue().executed())
                .map(Map.Entry::getKey)
                .filter(key -> !candidateCases.containsKey(key)
                        || !candidateCases.get(key).executed())
                .toList();
        if (newlySkipped.isEmpty()) {
            checks.add(Check.pass("execution_coverage", (double) baselineExecuted,
                    (double) candidateExecuted, "candidate covers baseline",
                    "no previously executed run became skipped"));
        } else {
            checks.add(Check.fail("execution_coverage", (double) baselineExecuted,
                    (double) candidateExecuted, "candidate covers baseline",
                    "newly skipped: " + summarizeKeys(newlySkipped)));
        }

        double baselinePassRate = passRate(baseline, suite, "baseline");
        double candidatePassRate = passRate(candidate, suite, "candidate");
        double passDelta = candidatePassRate - baselinePassRate;
        if (passDelta + EPSILON >= -policy.maxPassRateDrop()) {
            checks.add(Check.pass("pass_rate", baselinePassRate, candidatePassRate,
                    "delta >= -" + format(policy.maxPassRateDrop()),
                    "delta=" + format(passDelta)));
        } else {
            checks.add(Check.fail("pass_rate", baselinePassRate, candidatePassRate,
                    "delta >= -" + format(policy.maxPassRateDrop()),
                    "delta=" + format(passDelta)));
        }

        OptionalDouble baselineMedian = tokenMedian(baselineCases.values());
        OptionalDouble candidateMedian = tokenMedian(candidateCases.values());
        if (baselineMedian.isEmpty()) {
            checks.add(Check.skip("tokens_median", "baseline has no per-run token metric"));
        } else if (candidateMedian.isEmpty()) {
            checks.add(Check.fail("tokens_median", baselineMedian.getAsDouble(), null,
                    "ratio <= " + format(policy.maxTokenMedianRatio()),
                    "candidate has no per-run token metric"));
        } else {
            double baselineValue = baselineMedian.getAsDouble();
            double candidateValue = candidateMedian.getAsDouble();
            double ratio = baselineValue == 0
                    ? (candidateValue == 0 ? 1.0 : Double.POSITIVE_INFINITY)
                    : candidateValue / baselineValue;
            if (ratio <= policy.maxTokenMedianRatio() + EPSILON) {
                checks.add(Check.pass("tokens_median", baselineValue, candidateValue,
                        "ratio <= " + format(policy.maxTokenMedianRatio()),
                        "ratio=" + format(ratio)));
            } else {
                checks.add(Check.fail("tokens_median", baselineValue, candidateValue,
                        "ratio <= " + format(policy.maxTokenMedianRatio()),
                        "ratio=" + format(ratio)));
            }
        }

        OptionalDouble baselineGuard = minimumGuardRate(baselineCases.values());
        OptionalDouble candidateGuard = minimumGuardRate(candidateCases.values());
        if (baselineGuard.isEmpty() && candidateGuard.isEmpty()) {
            checks.add(Check.skip("guard_block_rate", "no safety score in this comparison"));
        } else if (candidateGuard.isEmpty()) {
            checks.add(Check.fail("guard_block_rate",
                    baselineGuard.isPresent() ? baselineGuard.getAsDouble() : null,
                    null,
                    "candidate >= " + format(policy.minGuardBlockRate()),
                    "candidate safety score missing"));
        } else {
            double candidateValue = candidateGuard.getAsDouble();
            Double baselineValue = baselineGuard.isPresent() ? baselineGuard.getAsDouble() : null;
            if (candidateValue + EPSILON >= policy.minGuardBlockRate()) {
                checks.add(Check.pass("guard_block_rate", baselineValue, candidateValue,
                        "candidate >= " + format(policy.minGuardBlockRate()),
                        "minimum safety score"));
            } else {
                checks.add(Check.fail("guard_block_rate", baselineValue, candidateValue,
                        "candidate >= " + format(policy.minGuardBlockRate()),
                        "minimum safety score"));
            }
        }

        return new ComparisonResult(suite, policy, List.copyOf(checks));
    }

    public static void write(Path outputDir, ComparisonResult result) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("compare.json"),
                MAPPER.writeValueAsString(buildJson(result)), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("compare.md"),
                buildMarkdown(result), StandardCharsets.UTF_8);
    }

    static ObjectNode buildJson(ComparisonResult result) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", COMPARE_SCHEMA_VERSION);
        root.put("passed", result.passed());
        if (result.suite() != null) {
            root.put("suite", result.suite());
        }
        ObjectNode policy = root.putObject("policy");
        policy.put("max_pass_rate_drop", result.policy().maxPassRateDrop());
        policy.put("max_token_median_ratio", result.policy().maxTokenMedianRatio());
        policy.put("min_guard_block_rate", result.policy().minGuardBlockRate());
        policy.put("require_case_set_match", result.policy().requireCaseSetMatch());

        ArrayNode checks = root.putArray("checks");
        for (Check check : result.checks()) {
            ObjectNode node = checks.addObject();
            node.put("metric", check.metric());
            node.put("status", check.status().name());
            putNullable(node, "baseline", check.baseline());
            putNullable(node, "candidate", check.candidate());
            node.put("requirement", check.requirement());
            node.put("detail", check.detail());
        }
        return root;
    }

    static String buildMarkdown(ComparisonResult result) {
        StringBuilder md = new StringBuilder();
        md.append("# PaiCLI Eval Compare\n\n");
        md.append("**result: ").append(result.passed() ? "PASS" : "FAIL").append("**");
        if (result.suite() != null) {
            md.append(" (suite=").append(result.suite()).append(')');
        }
        md.append("\n\n| metric | status | baseline | candidate | requirement | detail |\n");
        md.append("|--------|--------|----------|-----------|-------------|--------|\n");
        for (Check check : result.checks()) {
            md.append("| ").append(check.metric())
                    .append(" | ").append(check.status())
                    .append(" | ").append(display(check.baseline()))
                    .append(" | ").append(display(check.candidate()))
                    .append(" | ").append(escape(check.requirement()))
                    .append(" | ").append(escape(check.detail())).append(" |\n");
        }
        return md.toString();
    }

    private static JsonNode readReport(Path file, String label)
            throws IOException, CompareInputException {
        if (!Files.isRegularFile(file)) {
            throw new CompareInputException(label + " report does not exist: " + file);
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new CompareInputException(label + " report contains malformed JSON: " + file, e);
        }
    }

    private static void validateReport(JsonNode report, String label) throws CompareInputException {
        if (report == null || !report.isObject()) {
            throw new CompareInputException(label + " report must be a JSON object");
        }
        JsonNode version = report.path("schema_version");
        if (!version.isMissingNode() && (!version.isIntegralNumber()
                || !version.canConvertToInt() || version.asInt() != Reporter.REPORT_SCHEMA_VERSION)) {
            throw new CompareInputException(label + " report schema_version is incompatible: " + version);
        }
        if (!report.path("summary").isObject()) {
            throw new CompareInputException(label + " report is missing summary");
        }
        if (!report.path("cases").isArray()) {
            throw new CompareInputException(label + " report is missing cases[]");
        }
    }

    private static Map<RunKey, CaseView> cases(JsonNode report, String suite, String label)
            throws CompareInputException {
        Map<RunKey, CaseView> result = new LinkedHashMap<>();
        for (JsonNode node : report.path("cases")) {
            String caseSuite = node.path("suite").asText("");
            if (suite != null && !suite.equals(caseSuite)) {
                continue;
            }
            String id = node.path("id").asText("");
            JsonNode runIndexNode = node.path("run_index");
            if (id.isBlank() || caseSuite.isBlank() || !runIndexNode.isIntegralNumber()
                    || !runIndexNode.canConvertToInt() || runIndexNode.asInt() < 1) {
                throw new CompareInputException(label + " case has invalid id/suite/run_index");
            }
            RunKey key = new RunKey(caseSuite, id, runIndexNode.asInt());
            if (result.containsKey(key)) {
                throw new CompareInputException(label + " report contains duplicate run: " + key);
            }
            boolean executed = !node.hasNonNull("skip_reason");
            JsonNode tokensNode = node.get("tokens_total");
            Double tokens = null;
            if (tokensNode != null && !tokensNode.isNull()) {
                double value = tokensNode.asDouble(Double.NaN);
                if (!tokensNode.isNumber() || !Double.isFinite(value) || value < 0) {
                    throw new CompareInputException(
                            label + " case has invalid tokens_total: " + key);
                }
                tokens = value;
            }
            Double guard = guardRate(node.path("scores"), label, key);
            result.put(key, new CaseView(key, executed, tokens, guard));
        }
        if (result.isEmpty()) {
            throw new CompareInputException(label + " report has no cases for comparison"
                    + (suite == null ? "" : ": " + suite));
        }
        return result;
    }

    private static double passRate(JsonNode report, String suite, String label)
            throws CompareInputException {
        JsonNode node = suite == null
                ? report.path("summary").path("pass_rate")
                : report.path("summary").path("suites").path(suite).path("pass_rate");
        if (!node.isNumber()) {
            throw new CompareInputException(label + " report is missing pass_rate"
                    + (suite == null ? "" : " for suite " + suite));
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new CompareInputException(label + " pass_rate is outside 0-1: " + value);
        }
        return value;
    }

    private static OptionalDouble tokenMedian(Iterable<CaseView> cases) {
        List<Double> values = new ArrayList<>();
        for (CaseView view : cases) {
            if (view.executed() && view.tokens() != null) {
                values.add(view.tokens());
            }
        }
        if (values.isEmpty()) {
            return OptionalDouble.empty();
        }
        values.sort(Double::compareTo);
        int middle = values.size() / 2;
        double median = values.size() % 2 == 1
                ? values.get(middle)
                : (values.get(middle - 1) + values.get(middle)) / 2.0;
        return OptionalDouble.of(median);
    }

    private static OptionalDouble minimumGuardRate(Iterable<CaseView> cases) {
        double minimum = Double.POSITIVE_INFINITY;
        boolean found = false;
        for (CaseView view : cases) {
            if (view.executed() && view.guardBlockRate() != null) {
                minimum = Math.min(minimum, view.guardBlockRate());
                found = true;
            }
        }
        return found ? OptionalDouble.of(minimum) : OptionalDouble.empty();
    }

    private static Double guardRate(JsonNode scores, String label, RunKey key)
            throws CompareInputException {
        if (!scores.isArray()) {
            return null;
        }
        Double minimum = null;
        for (JsonNode score : scores) {
            if ("guard_block_rate".equals(score.path("name").asText())) {
                JsonNode valueNode = score.path("value");
                double value = valueNode.asDouble(Double.NaN);
                if (!valueNode.isNumber() || !Double.isFinite(value)
                        || value < 0 || value > 1) {
                    throw new CompareInputException(
                            label + " case has invalid guard_block_rate: " + key);
                }
                minimum = minimum == null ? value : Math.min(minimum, value);
            }
        }
        return minimum;
    }

    private static String caseSetMismatch(Set<RunKey> baseline, Set<RunKey> candidate) {
        Set<RunKey> missing = new LinkedHashSet<>(baseline);
        missing.removeAll(candidate);
        Set<RunKey> added = new LinkedHashSet<>(candidate);
        added.removeAll(baseline);
        return "case set mismatch; missing=" + summarizeKeys(missing)
                + ", added=" + summarizeKeys(added);
    }

    private static String summarizeKeys(Iterable<RunKey> keys) {
        List<String> values = new ArrayList<>();
        for (RunKey key : keys) {
            if (values.size() == 5) {
                values.add("...");
                break;
            }
            values.add(key.toString());
        }
        return values.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Set<String> known = Set.of("baseline", "candidate", "suite", "policy", "out", "help");
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (!known.contains(key)) {
                throw new IllegalArgumentException("unknown option: " + arg);
            }
            if ("help".equals(key)) {
                options.put(key, "true");
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing option value: " + arg);
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return Path.of(value);
    }

    private static String normalizeSuite(String suite) {
        return suite == null || suite.isBlank() ? null : suite.strip();
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String display(Double value) {
        return value == null || !Double.isFinite(value) ? "n/a" : format(value);
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return "infinity";
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                Usage: EvalCompare --baseline <report.json> --candidate <report.json> [options]
                  --suite <name>    compare one suite instead of the aggregate
                  --policy <json>   override deterministic thresholds
                  --out <dir>       write compare.json and compare.md
                  --help            show this help
                """);
    }

    public record ComparisonResult(String suite, ComparePolicy policy, List<Check> checks) {
        public boolean passed() {
            return checks.stream().noneMatch(c -> c.status() == Status.FAIL);
        }
    }

    public record Check(String metric,
                        Status status,
                        Double baseline,
                        Double candidate,
                        String requirement,
                        String detail) {
        static Check pass(String metric, Double baseline, Double candidate,
                          String requirement, String detail) {
            return new Check(metric, Status.PASS, baseline, candidate, requirement, detail);
        }

        static Check fail(String metric, Double baseline, Double candidate,
                          String requirement, String detail) {
            return new Check(metric, Status.FAIL, baseline, candidate, requirement, detail);
        }

        static Check skip(String metric, String detail) {
            return new Check(metric, Status.SKIP, null, null, "n/a", detail);
        }
    }

    public enum Status {
        PASS,
        FAIL,
        SKIP
    }

    private record RunKey(String suite, String caseId, int runIndex) {
        @Override
        public String toString() {
            return suite + "/" + caseId + "#" + runIndex;
        }
    }

    private record CaseView(RunKey key,
                            boolean executed,
                            Double tokens,
                            Double guardBlockRate) {
    }

    public static class CompareInputException extends Exception {
        public CompareInputException(String message) {
            super(message);
        }

        public CompareInputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
