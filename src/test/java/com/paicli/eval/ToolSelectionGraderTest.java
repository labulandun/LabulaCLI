package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSelectionGraderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GraderSpec spec(String mode, String... expected) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_selection");
        if (mode != null) {
            node.put("mode", mode);
        }
        ArrayNode array = node.putArray("expected");
        for (String tool : expected) {
            array.add(tool);
        }
        return new GraderSpec("tool_selection", node);
    }

    private static RunArtifact artifactWithTools(String... toolNames) {
        List<RunArtifact.ToolCallStat> tools = java.util.Arrays.stream(toolNames)
                .map(name -> new RunArtifact.ToolCallStat(name, "OK", 100))
                .toList();
        return new RunArtifact("c1", 1, "completed", "", null, null, null, 1000, 0, 0, 0,
                RunArtifact.LlmStats.EMPTY, tools);
    }

    private static CaseSpec caseSpec() {
        return new CaseSpec("c1", "tool-golden", "prompt", 300, 1, "host", Path.of("."), null, List.of());
    }

    @Test
    void firstSearchModeChecksFirstSearchToolOnly() {
        ToolSelectionGrader grader = new ToolSelectionGrader(spec(null, "grep_code"));
        // read_file 不是检索类工具，首个检索类是 grep_code → 通过
        assertTrue(grader.grade(caseSpec(), artifactWithTools("read_file", "grep_code"), Path.of("."))
                .get(0).passed());
        // 首个检索类是 search_code → 不通过
        assertFalse(grader.grade(caseSpec(), artifactWithTools("search_code", "grep_code"), Path.of("."))
                .get(0).passed());
    }

    @Test
    void firstAndAnyModes() {
        assertTrue(new ToolSelectionGrader(spec("first", "glob_files"))
                .grade(caseSpec(), artifactWithTools("glob_files", "read_file"), Path.of("."))
                .get(0).passed());
        assertFalse(new ToolSelectionGrader(spec("first", "glob_files"))
                .grade(caseSpec(), artifactWithTools("read_file", "glob_files"), Path.of("."))
                .get(0).passed());
        assertTrue(new ToolSelectionGrader(spec("any", "read_file"))
                .grade(caseSpec(), artifactWithTools("grep_code", "read_file"), Path.of("."))
                .get(0).passed());
    }

    @Test
    void noToolSpansFailsInsteadOfThrowing() {
        List<Score> scores = new ToolSelectionGrader(spec(null, "grep_code"))
                .grade(caseSpec(), artifactWithTools(), Path.of("."));
        assertFalse(scores.get(0).passed());
        assertTrue(scores.get(0).comment().contains("没有 tool span"));
    }

    @Test
    void missingExpectedOrUnknownModeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ToolSelectionGrader(spec(null))
                .grade(caseSpec(), artifactWithTools("grep_code"), Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> new ToolSelectionGrader(spec("bogus", "grep_code"))
                .grade(caseSpec(), artifactWithTools("grep_code"), Path.of(".")));
    }
}
