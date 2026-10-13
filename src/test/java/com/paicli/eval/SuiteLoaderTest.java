package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.ignore.IgnoreNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuiteLoaderTest {

    @TempDir
    Path evalHome;

    private Path writeCase(String suite, String dirName, String json) throws Exception {
        Path caseDir = evalHome.resolve("suites").resolve(suite).resolve(dirName);
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("case.json"), json, StandardCharsets.UTF_8);
        return caseDir;
    }

    @Test
    void loadsCasesSortedAndParsesFields() throws Exception {
        writeCase("custom", "case-b", """
                {"id":"case-b","prompt":"do b",
                 "graders":[{"type":"output_assert","contains":"x"}]}
                """);
        Path caseADir = writeCase("custom", "case-a", """
                {"id":"case-a","prompt":"do a","timeout_seconds":120,"runs":2,
                 "workspace":{"fixture":"fixture"},
                 "graders":[{"type":"command","cmd":"echo hi"},{"type":"file_assert","path":"f.txt"}]}
                """);
        Files.createDirectories(caseADir.resolve("fixture"));

        List<CaseSpec> cases = SuiteLoader.load(evalHome, List.of("custom"), null);
        assertEquals(2, cases.size());
        assertEquals("case-a", cases.get(0).id());
        assertEquals(120, cases.get(0).timeoutSeconds());
        assertEquals(2, cases.get(0).runs());
        assertEquals("host", cases.get(0).sandbox());
        assertNotNull(cases.get(0).fixtureDir());
        assertEquals(2, cases.get(0).graders().size());
        assertEquals("case-b", cases.get(1).id());
        assertEquals(CaseSpec.DEFAULT_TIMEOUT_SECONDS, cases.get(1).timeoutSeconds());
    }

    @Test
    void caseFilterKeepsOnlyMatchingId() throws Exception {
        writeCase("custom", "case-a", """
                {"id":"case-a","prompt":"p","graders":[{"type":"output_assert","contains":"x"}]}
                """);
        writeCase("custom", "case-b", """
                {"id":"case-b","prompt":"p","graders":[{"type":"output_assert","contains":"x"}]}
                """);
        List<CaseSpec> cases = SuiteLoader.load(evalHome, List.of("custom"), "case-b");
        assertEquals(1, cases.size());
        assertEquals("case-b", cases.get(0).id());
    }

    @Test
    void invalidCasesFailFast() throws Exception {
        writeCase("custom", "no-graders", "{\"id\":\"no-graders\",\"prompt\":\"p\",\"graders\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> SuiteLoader.load(evalHome, List.of("custom"), null));
        assertThrows(IllegalArgumentException.class,
                () -> SuiteLoader.load(evalHome, List.of("missing-suite"), null));
    }

    @Test
    void directoriesWithoutCaseJsonAreIgnored() throws Exception {
        writeCase("custom", "case-a", """
                {"id":"case-a","prompt":"p","graders":[{"type":"output_assert","contains":"x"}]}
                """);
        Files.createDirectories(evalHome.resolve("suites").resolve("custom").resolve("not-a-case"));
        List<CaseSpec> cases = SuiteLoader.load(evalHome, List.of("custom"), null);
        assertEquals(1, cases.size());
        assertTrue(cases.get(0).graders().get(0).type().equals("output_assert"));
    }

    @Test
    void gitWorkspacesRequirePinnedCommit() throws Exception {
        Path caseDir = writeCase("swe-lite", "git-case", """
                {"id":"git-case","prompt":"fix it",
                 "workspace":{"git":{"repo":"repo"}},
                 "graders":[{"type":"output_assert","contains":"ok"}]}
                """);
        Files.createDirectories(caseDir.resolve("repo"));

        assertThrows(IllegalArgumentException.class,
                () -> SuiteLoader.load(evalHome, List.of("swe-lite"), null));

        writeCase("swe-lite", "git-case", """
                {"id":"git-case","prompt":"fix it",
                 "workspace":{"git":{"repo":"repo","commit":"abc123"}},
                 "graders":[{"type":"output_assert","contains":"ok"}]}
                """);
        List<CaseSpec> cases = SuiteLoader.load(evalHome, List.of("swe-lite"), null);
        assertEquals(1, cases.size());
        assertEquals("abc123", cases.get(0).gitSource().commit());
    }

    @Test
    void terminalLogFixtureIsNotIgnoredAndSuiteLoads() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        String fixturePath = "eval/suites/terminal/tm-002-log-triage/fixture/app.log";

        IgnoreNode ignoreNode = new IgnoreNode();
        try (var input = Files.newInputStream(projectRoot.resolve(".gitignore"))) {
            ignoreNode.parse(input);
        }
        assertEquals(IgnoreNode.MatchResult.NOT_IGNORED,
                ignoreNode.isIgnored(fixturePath, false),
                "terminal 日志 fixture 不能被项目 .gitignore 排除");

        List<CaseSpec> cases = SuiteLoader.load(projectRoot.resolve("eval"), List.of("terminal"), null);
        assertEquals(5, cases.size());
        CaseSpec logTriage = cases.stream()
                .filter(spec -> "tm-002-log-triage".equals(spec.id()))
                .findFirst()
                .orElseThrow();
        Path appLog = logTriage.fixtureDir().resolve("app.log");
        assertTrue(Files.isRegularFile(appLog));
        assertTrue(Files.readString(appLog, StandardCharsets.UTF_8).contains("active_waiters=31"));
    }
}
