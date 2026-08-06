package com.paicli.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the append-only artifact contract consumed by E1-E4 reports and external tooling. */
class E1E4ArtifactContractTest {

    @Test
    void artifactKeepsLegacyCamelCaseFieldsAndAppendsVersionAndScores() {
        RunArtifact artifact = new RunArtifact("contract-1", 2, "completed", "answer", null,
                "trace-1", "obs-1", 1234, 2, 5, 1,
                new RunArtifact.LlmStats(3, 100, 20, 30, 150),
                List.of(new RunArtifact.ToolCallStat("read_file", "OK", 10)),
                List.of());
        CaseResult result = new CaseResult("contract-1", "custom", 2, artifact,
                List.of(Score.bool("tests_pass", true, "ok")), true, null);

        ObjectNode json = EvalMain.buildArtifactJson(result);

        assertEquals(EvalMain.ARTIFACT_SCHEMA_VERSION, json.path("schema_version").asInt());
        assertEquals("contract-1", json.path("caseId").asText());
        assertEquals(2, json.path("runIndex").asInt());
        assertEquals("trace-1", json.path("traceId").asText());
        assertEquals("obs-1", json.path("rootObservationId").asText());
        assertEquals(150, json.path("llm").path("totalTokens").asLong());
        assertEquals("read_file", json.path("tools").get(0).path("name").asText());
        assertTrue(json.path("task_success").asBoolean());
        assertEquals("BOOLEAN", json.path("scores").get(0).path("data_type").asText());
        assertFalse(json.has("case_id"));
        assertFalse(json.has("run_index"));
    }

    @Test
    void skippedArtifactIsPersistableAndExplicitlyNonGating() {
        CaseResult skipped = CaseResult.skipped(EvalFixtures.caseSpec("docker-case"), "docker unavailable");
        ObjectNode json = EvalMain.buildArtifactJson(skipped);

        assertEquals("skipped", json.path("status").asText());
        assertFalse(json.path("task_success").asBoolean());
        assertEquals("docker unavailable", json.path("skip_reason").asText());
        assertTrue(json.path("scores").isEmpty());
    }
}
