package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    private static GraderSpec spec(String type, String... kv) {
        return EvalFixtures.graderSpec(type, kv);
    }

    private static RunArtifact artifact(String answer) {
        return EvalFixtures.artifact("case-x", "completed", answer);
    }

    private CaseSpec caseSpec() {
        return EvalFixtures.caseSpec("case-x", "custom", workspace, null);
    }

    @Test
    void outputAssertChecksContainsRegexAndNotContains() {
        List<Score> pass = new OutputAssertGrader(spec("output_assert", "regex", "(?i)token\\s*bucket"))
                .grade(caseSpec(), artifact("This is a Token Bucket limiter"), workspace);
        assertTrue(pass.get(0).passed());

        List<Score> fail = new OutputAssertGrader(spec("output_assert", "contains", "missing"))
                .grade(caseSpec(), artifact("something else"), workspace);
        assertFalse(fail.get(0).passed());

        List<Score> notContains = new OutputAssertGrader(spec("output_assert", "not_contains", "秘密"))
                .grade(caseSpec(), artifact("回答里有秘密"), workspace);
        assertFalse(notContains.get(0).passed());
    }

    @Test
    void outputAssertWithoutAnyRuleFails() {
        List<Score> scores = new OutputAssertGrader(spec("output_assert"))
                .grade(caseSpec(), artifact("any"), workspace);
        assertFalse(scores.get(0).passed());
        assertTrue(scores.get(0).comment().contains("断言缺少"));
    }

    @Test
    void fileAssertChecksExistenceAndContent() throws Exception {
        Files.writeString(workspace.resolve("app.properties"),
                "db.password=secret123\n", StandardCharsets.UTF_8);

        List<Score> pass = new FileAssertGrader(
                spec("file_assert", "path", "app.properties", "contains", "db.password=secret123"))
                .grade(caseSpec(), artifact(""), workspace);
        assertTrue(pass.get(0).passed());

        List<Score> missing = new FileAssertGrader(spec("file_assert", "path", "absent.txt"))
                .grade(caseSpec(), artifact(""), workspace);
        assertFalse(missing.get(0).passed());

        assertThrows(IllegalArgumentException.class, () -> new FileAssertGrader(
                spec("file_assert", "path", "../escape.txt"))
                .grade(caseSpec(), artifact(""), workspace));
    }

    @Test
    void commandGraderScoresByExitCode() throws Exception {
        CommandRunner runner = new CommandRunner.Host();
        List<Score> pass = new CommandGrader(spec("command", "cmd", "echo eval-ok"), runner)
                .grade(caseSpec(), artifact(""), workspace);
        assertTrue(pass.get(0).passed());
        assertEquals("tests_pass", pass.get(0).name());
        assertTrue(pass.get(0).comment().contains("eval-ok"));
        assertTrue(pass.get(0).comment().contains("@host"));

        List<Score> fail = new CommandGrader(spec("command", "cmd", "exit 3"), runner)
                .grade(caseSpec(), artifact(""), workspace);
        assertFalse(fail.get(0).passed());
    }

    @Test
    void graderFactoryRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> GraderFactory.create(spec("no_such_grader"), new CommandRunner.Host()));
    }
}
