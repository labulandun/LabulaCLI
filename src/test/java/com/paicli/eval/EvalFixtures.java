package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试共用的构造夹具。CaseSpec / RunArtifact 都是宽 record，
 * 集中在这里构造，字段增减时只改一处。
 */
final class EvalFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalFixtures() {
    }

    static CaseSpec caseSpec(String id) {
        return caseSpec(id, "custom", Path.of("."), null);
    }

    static CaseSpec caseSpec(String id, String suite, Path caseDir, Path fixtureDir) {
        return new CaseSpec(id, suite, "prompt", 600, 1, "host", null, caseDir, fixtureDir, List.of());
    }

    static RunArtifact artifact(String caseId) {
        return artifact(caseId, "completed", "");
    }

    static RunArtifact artifact(String caseId, String status, String finalAnswer) {
        return new RunArtifact(caseId, 1, status, finalAnswer, null, null, null, 1000,
                0, 0, 0, RunArtifact.LlmStats.EMPTY, List.of(), List.of());
    }

    static RunArtifact artifactWithTools(String... toolNames) {
        List<RunArtifact.ToolCallStat> tools = java.util.Arrays.stream(toolNames)
                .map(name -> new RunArtifact.ToolCallStat(name, "OK", 100))
                .toList();
        return new RunArtifact("c1", 1, "completed", "", null, null, null, 1000,
                0, 0, 0, RunArtifact.LlmStats.EMPTY, tools, List.of());
    }

    static RunArtifact artifactWithAudit(List<AuditTrail.AuditRecord> audit) {
        return new RunArtifact("c1", 1, "completed", "", null, null, null, 1000,
                0, 0, 0, RunArtifact.LlmStats.EMPTY, List.of(), audit);
    }

    /** 把 key/value 对拼成 grader 参数节点。 */
    static GraderSpec graderSpec(String type, String... kv) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            node.put(kv[i], kv[i + 1]);
        }
        return new GraderSpec(type, node);
    }
}
