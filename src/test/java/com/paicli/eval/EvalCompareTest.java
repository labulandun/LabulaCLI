package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCompareTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @TempDir
    Path tempDir;

    @Test
    void exactThresholdBoundariesPass() throws Exception {
        ObjectNode baseline = report(1.0, new double[]{100, 200}, 1.0);
        ObjectNode candidate = report(0.95, new double[]{130, 260}, 1.0);

        EvalCompare.ComparisonResult result =
                EvalCompare.compare(baseline, candidate, ComparePolicy.defaults(), null);

        assertTrue(result.passed());
        assertEquals(EvalCompare.Status.PASS, check(result, "pass_rate").status());
        assertEquals(EvalCompare.Status.PASS, check(result, "tokens_median").status());
        assertEquals(EvalCompare.Status.PASS, check(result, "guard_block_rate").status());
    }

    @Test
    void passTokenAndSafetyRegressionsFailIndependently() throws Exception {
        ObjectNode baseline = report(1.0, new double[]{100, 200}, 1.0);

        EvalCompare.ComparisonResult passRegression = EvalCompare.compare(
                baseline, report(0.949, new double[]{100, 200}, 1.0),
                ComparePolicy.defaults(), null);
        EvalCompare.ComparisonResult tokenRegression = EvalCompare.compare(
                baseline, report(1.0, new double[]{131, 260}, 1.0),
                ComparePolicy.defaults(), null);
        EvalCompare.ComparisonResult safetyRegression = EvalCompare.compare(
                baseline, report(1.0, new double[]{100, 200}, 0.9999),
                ComparePolicy.defaults(), null);

        assertEquals(EvalCompare.Status.FAIL, check(passRegression, "pass_rate").status());
        assertEquals(EvalCompare.Status.FAIL, check(tokenRegression, "tokens_median").status());
        assertEquals(EvalCompare.Status.FAIL, check(safetyRegression, "guard_block_rate").status());
        assertFalse(passRegression.passed());
        assertFalse(tokenRegression.passed());
        assertFalse(safetyRegression.passed());
    }

    @Test
    void legacyBaselineWithoutTokenMetricStillComparesPassRate() throws Exception {
        ObjectNode baseline = report(0.8, new double[]{100, 200}, null);
        baseline.remove("schema_version");
        for (var item : baseline.withArray("cases")) {
            ((ObjectNode) item).remove("tokens_total");
        }
        ObjectNode candidate = report(0.8, new double[]{110, 210}, null);

        EvalCompare.ComparisonResult result =
                EvalCompare.compare(baseline, candidate, ComparePolicy.defaults(), null);

        assertTrue(result.passed());
        assertEquals(EvalCompare.Status.SKIP, check(result, "tokens_median").status());
        assertEquals(EvalCompare.Status.SKIP, check(result, "guard_block_rate").status());
    }

    @Test
    void mismatchedCaseSetIsInputErrorAndNewSkipIsRegression() throws Exception {
        ObjectNode baseline = report(1.0, new double[]{100, 200}, null);
        ObjectNode mismatched = report(1.0, new double[]{100}, null);
        assertThrows(EvalCompare.CompareInputException.class,
                () -> EvalCompare.compare(baseline, mismatched, ComparePolicy.defaults(), null));

        ObjectNode skipped = report(1.0, new double[]{100, 200}, null);
        ((ObjectNode) skipped.withArray("cases").get(0)).put("skip_reason", "docker unavailable");
        EvalCompare.ComparisonResult result =
                EvalCompare.compare(baseline, skipped, ComparePolicy.defaults(), null);
        assertEquals(EvalCompare.Status.FAIL, check(result, "execution_coverage").status());
    }

    @Test
    void malformedReportContractsAreInputErrors() throws Exception {
        ObjectNode baseline = report(1.0, new double[]{100, 200}, 1.0);

        ObjectNode invalidVersion = report(1.0, new double[]{100, 200}, 1.0);
        invalidVersion.put("schema_version", 1.5);
        assertThrows(EvalCompare.CompareInputException.class,
                () -> EvalCompare.compare(baseline, invalidVersion, ComparePolicy.defaults(), null));

        ObjectNode invalidRun = report(1.0, new double[]{100, 200}, 1.0);
        ((ObjectNode) invalidRun.withArray("cases").get(0)).put("run_index", 1.5);
        assertThrows(EvalCompare.CompareInputException.class,
                () -> EvalCompare.compare(baseline, invalidRun, ComparePolicy.defaults(), null));

        ObjectNode invalidTokens = report(1.0, new double[]{100, 200}, 1.0);
        ((ObjectNode) invalidTokens.withArray("cases").get(0)).put("tokens_total", "100");
        assertThrows(EvalCompare.CompareInputException.class,
                () -> EvalCompare.compare(baseline, invalidTokens, ComparePolicy.defaults(), null));

        ObjectNode invalidGuard = report(1.0, new double[]{100, 200}, 1.0);
        ((ObjectNode) invalidGuard.withArray("cases").get(0)
                .withArray("scores").get(0)).put("value", 1.1);
        assertThrows(EvalCompare.CompareInputException.class,
                () -> EvalCompare.compare(baseline, invalidGuard, ComparePolicy.defaults(), null));
    }

    @Test
    void suiteSelectionUsesSuitePassRate() throws Exception {
        ObjectNode baseline = report(0.1, new double[]{100, 100}, null);
        ObjectNode candidate = report(0.1, new double[]{100, 100}, null);
        baseline.path("summary").path("suites").path("custom")
                .deepCopy().getClass();
        ((ObjectNode) baseline.path("summary").path("suites").path("custom")).put("pass_rate", 1.0);
        ((ObjectNode) candidate.path("summary").path("suites").path("custom")).put("pass_rate", 0.96);

        EvalCompare.ComparisonResult result =
                EvalCompare.compare(baseline, candidate, ComparePolicy.defaults(), "custom");

        assertTrue(result.passed());
        assertEquals(1.0, check(result, "pass_rate").baseline());
        assertEquals(0.96, check(result, "pass_rate").candidate());
    }

    @Test
    void cliReturnsZeroOneAndTwoAndWritesReports() throws Exception {
        Path baseline = tempDir.resolve("baseline.json");
        Path candidate = tempDir.resolve("candidate.json");
        Files.writeString(baseline, MAPPER.writeValueAsString(
                report(1.0, new double[]{100, 200}, null)), StandardCharsets.UTF_8);
        Files.writeString(candidate, MAPPER.writeValueAsString(
                report(0.94, new double[]{100, 200}, null)), StandardCharsets.UTF_8);
        Path out = tempDir.resolve("compare-out");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int regression = EvalCompare.run(new String[]{
                        "--baseline", baseline.toString(),
                        "--candidate", candidate.toString(),
                        "--out", out.toString()},
                new PrintStream(stdout), new PrintStream(stderr));
        assertEquals(1, regression);
        assertTrue(Files.isRegularFile(out.resolve("compare.json")));
        assertTrue(Files.isRegularFile(out.resolve("compare.md")));

        Files.writeString(candidate, MAPPER.writeValueAsString(
                report(0.96, new double[]{100, 200}, null)), StandardCharsets.UTF_8);
        int pass = EvalCompare.run(new String[]{
                        "--baseline", baseline.toString(),
                        "--candidate", candidate.toString()},
                new PrintStream(stdout), new PrintStream(stderr));
        assertEquals(0, pass);

        Files.writeString(candidate, "{broken", StandardCharsets.UTF_8);
        int inputError = EvalCompare.run(new String[]{
                        "--baseline", baseline.toString(),
                        "--candidate", candidate.toString()},
                new PrintStream(stdout), new PrintStream(stderr));
        assertEquals(2, inputError);
    }

    @Test
    void evalMainBaselineIntegrationWritesReportsAndRejectsMissingPolicy() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path evalHome = projectRoot.resolve("eval");
        Path runDir = projectRoot.resolve("runs/run-1");
        Path baseline = projectRoot.resolve("baseline.json");
        Files.createDirectories(runDir);
        Files.writeString(baseline, MAPPER.writeValueAsString(
                report(1.0, new double[]{100, 200}, 1.0)), StandardCharsets.UTF_8);
        Files.writeString(runDir.resolve("report.json"), MAPPER.writeValueAsString(
                report(1.0, new double[]{100, 200}, 1.0)), StandardCharsets.UTF_8);

        int passed = EvalMain.compareBaseline(
                java.util.Map.of("baseline", baseline.toString()),
                projectRoot, evalHome, runDir);
        assertEquals(0, passed);
        assertTrue(Files.isRegularFile(runDir.resolve("compare.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("compare.md")));

        int inputError = EvalMain.compareBaseline(java.util.Map.of(
                        "baseline", baseline.toString(), "compare-policy", "missing.json"),
                projectRoot, evalHome, runDir);
        assertEquals(2, inputError);
    }

    @Test
    void policyFileLoadsAndValidates() throws Exception {
        Path policyFile = tempDir.resolve("policy.json");
        Files.writeString(policyFile, """
                {
                  "schema_version": 1,
                  "max_pass_rate_drop": 0.1,
                  "max_token_median_ratio": 1.5,
                  "min_guard_block_rate": 0.9,
                  "require_case_set_match": false
                }
                """, StandardCharsets.UTF_8);
        ComparePolicy policy = ComparePolicy.load(policyFile);
        assertEquals(0.1, policy.maxPassRateDrop());
        assertFalse(policy.requireCaseSetMatch());

        Files.writeString(policyFile, "{\"max_pass_rate_drop\": 2}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ComparePolicy.load(policyFile));

        Files.writeString(policyFile, "{\"schema_version\":2}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ComparePolicy.load(policyFile));
        Files.writeString(policyFile, "{\"max_pass_rate_drop\":\"0.1\"}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ComparePolicy.load(policyFile));
        Files.writeString(policyFile, "{\"max_passrate_drop\":0.1}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ComparePolicy.load(policyFile));
        Files.writeString(policyFile, "[]", StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> ComparePolicy.load(policyFile));
    }

    private static EvalCompare.Check check(EvalCompare.ComparisonResult result, String name) {
        EvalCompare.Check check = result.checks().stream()
                .filter(c -> name.equals(c.metric()))
                .findFirst()
                .orElse(null);
        assertNotNull(check);
        return check;
    }

    private static ObjectNode report(double passRate, double[] tokens, Double guardRate) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", Reporter.REPORT_SCHEMA_VERSION);
        ObjectNode summary = root.putObject("summary");
        summary.put("pass_rate", passRate);
        summary.putObject("suites").putObject("custom").put("pass_rate", passRate);
        ArrayNode cases = root.putArray("cases");
        for (int i = 0; i < tokens.length; i++) {
            ObjectNode item = cases.addObject();
            item.put("id", "case-" + i);
            item.put("suite", "custom");
            item.put("run_index", 1);
            item.put("status", "completed");
            item.put("task_success", true);
            item.put("tokens_total", tokens[i]);
            ArrayNode scores = item.putArray("scores");
            if (guardRate != null) {
                ObjectNode score = scores.addObject();
                score.put("name", "guard_block_rate");
                score.put("value", guardRate);
                score.put("data_type", "NUMERIC");
                score.put("comment", "safety");
            }
        }
        return root;
    }
}
