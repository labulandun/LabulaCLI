package com.paicli.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReporterTest {

    private static CaseSpec spec(String id) {
        return EvalFixtures.caseSpec(id);
    }

    private static CaseResult passed(String id) {
        RunArtifact artifact = new RunArtifact(id, 1, "completed", "ok", null, "trace-1", "obs-1",
                4000, 1, 5, 2,
                new RunArtifact.LlmStats(3, 900, 100, 200, 1200),
                List.of(new RunArtifact.ToolCallStat("grep_code", "OK", 100),
                        new RunArtifact.ToolCallStat("execute_command", "ERROR", 300)),
                List.of(new AuditTrail.AuditRecord("execute_command", "rm -rf /", "deny",
                        "禁止 rm -rf 删除全盘或用户目录", "policy")));
        return CaseResult.of(spec(id), 1, artifact,
                List.of(Score.bool("tests_pass", true, "ok")));
    }

    private static CaseResult failed(String id) {
        RunArtifact artifact = EvalFixtures.artifact(id, "completed", "no");
        return CaseResult.of(spec(id), 1, artifact,
                List.of(Score.bool("tests_pass", false, "exit=1 cmd=java Check\nFAIL detail")));
    }

    @Test
    void jsonReportAggregatesPassRateAndSkips() {
        Map<String, String> meta = new LinkedHashMap<>(Map.of("run_id", "r1"));
        List<CaseResult> results = List.of(passed("c1"), failed("c2"),
                CaseResult.skipped(spec("c3"), "sandbox docker 未支持"));
        ObjectNode report = Reporter.buildJson(meta, results);

        assertEquals(3, report.path("summary").path("total").asInt());
        assertEquals(2, report.path("summary").path("executed").asInt());
        assertEquals(1, report.path("summary").path("skipped").asInt());
        assertEquals(1, report.path("summary").path("passed").asInt());
        assertEquals(0.5, report.path("summary").path("pass_rate").asDouble());
        assertEquals(0.5, report.path("summary").path("suites").path("custom").path("pass_rate").asDouble());
        assertEquals("trace-1", report.path("cases").get(0).path("trace_id").asText());
        assertTrue(report.path("cases").get(2).path("skip_reason").asText().contains("docker"));
        assertEquals(1200, report.path("summary").path("tokens_total").asLong());
        assertEquals(2, report.path("summary").path("tool_calls").asInt());
        assertEquals(0.5, report.path("summary").path("tool_error_rate").asDouble());
        assertEquals(1200, report.path("cases").get(0).path("tokens_total").asLong());
        assertEquals(3, report.path("cases").get(0).path("llm_rounds").asInt());
        assertEquals(1, report.path("cases").get(0).path("tool_errors").asLong());
    }

    @Test
    void turnFailureOrEmptyScoresNeverCountAsSuccess() {
        RunArtifact failedTurn = EvalFixtures.artifact("c1", "failed", "");
        assertFalse(CaseResult.of(spec("c1"), 1, failedTurn,
                List.of(Score.bool("tests_pass", true, "ok"))).taskSuccess());

        RunArtifact completed = EvalFixtures.artifact("c2", "completed", "ok");
        assertFalse(CaseResult.of(spec("c2"), 1, completed, List.of()).taskSuccess());
    }

    @Test
    void markdownListsFailedCaseDetails() {
        Map<String, String> meta = Map.of("run_id", "r1");
        String md = Reporter.buildMarkdown(meta, List.of(passed("c1"), failed("c2")));
        assertTrue(md.contains("pass_rate: 1/2"));
        assertTrue(md.contains("| c1 |"));
        assertTrue(md.contains("## 失败明细"));
        assertTrue(md.contains("### c2"));
        assertTrue(md.contains("FAIL detail"));
    }
}
