package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase E4 用例资产契约：在启动模型或 Docker 之前尽早发现缺文件、漂移配置。 */
class E4SuiteContractTest {

    private static final Path EVAL_HOME = Path.of("eval").toAbsolutePath().normalize();
    @TempDir
    Path tempDir;


    @Test
    void phaseE4SuitesLoadWithCompleteWorkspaceAssets() throws Exception {
        List<CaseSpec> terminal = SuiteLoader.load(EVAL_HOME, List.of("terminal"), null);
        List<CaseSpec> sweLite = SuiteLoader.load(EVAL_HOME, List.of("swe-lite"), null);

        assertEquals(5, terminal.size(), "terminal suite 应固定包含 5 条基础用例");
        assertEquals(Set.of(
                        "tm-001-fix-build-script",
                        "tm-002-log-triage",
                        "tm-003-compile-error",
                        "tm-004-missing-config",
                        "tm-005-pipeline-order"),
                terminal.stream().map(CaseSpec::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(terminal.stream().allMatch(spec -> "docker".equals(spec.sandbox())),
                "terminal 用例必须显式使用 Docker 沙盒");
        assertFalse(sweLite.isEmpty(), "swe-lite suite 至少应可加载现有基础用例");

        assertEquals(10, sweLite.size(), "swe-lite suite must contain the ten E4 cases");
        assertEquals(Set.of(
                        "swe-001-negative-money-format",
                        "swe-002-null-category-crash",
                        "swe-003-report-subtotals",
                        "swe-004-csv-quoted-fields",
                        "swe-005-date-range-inclusive",
                        "swe-006-lru-access-order",
                        "swe-007-safe-path-join",
                        "swe-008-retry-delay-cap",
                        "swe-009-task-queue-shutdown",
                        "swe-010-properties-preserve"),
                sweLite.stream().map(CaseSpec::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(sweLite.stream().flatMap(spec -> spec.graders().stream())
                        .allMatch(grader -> grader.text("cmd", "").contains("javac -encoding UTF-8")),
                "swe-lite javac graders must pin UTF-8 for cross-platform model edits");

        List<CaseSpec> all = java.util.stream.Stream.concat(terminal.stream(), sweLite.stream()).toList();
        assertEquals(all.size(), new HashSet<>(all.stream().map(CaseSpec::id).toList()).size(),
                "E4 case id 不能重复");
        for (CaseSpec spec : all) {
            assertWorkspaceSourceComplete(spec);
            assertReferencedCaseFilesExist(spec);
        }
    }

    @Test
    void terminalShellFixturesUseUnixLineEndings() throws Exception {
        try (var files = Files.walk(EVAL_HOME.resolve("suites/terminal"))) {
            for (Path script : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sh"))
                    .toList()) {
                byte[] content = Files.readAllBytes(script);
                for (byte value : content) {
                    assertFalse(value == '\r', "Docker shell fixture 不能包含 CR/CRLF: " + script);
                }
            }
        }
    }


    @Test
    void sweLiteBaselinesFailTargetTestsAndPassRegressionTests() throws Exception {
        List<CaseSpec> cases = SuiteLoader.load(EVAL_HOME, List.of("swe-lite"), null);
        for (CaseSpec spec : cases) {
            Workspace workspace = Workspace.provision(spec, tempDir.resolve(spec.id()));
            try {
                Set<String> graderNames = new HashSet<>();
                for (GraderSpec graderSpec : spec.graders()) {
                    String name = graderSpec.text("name", "tests_pass");
                    graderNames.add(name);
                    List<Score> scores = new CommandGrader(graderSpec, new CommandRunner.Host())
                            .grade(spec, EvalFixtures.artifact(spec.id()), workspace.root());
                    assertEquals(1, scores.size(), "one score per command grader: " + spec.id());
                    if ("fail_to_pass".equals(name)) {
                        assertFalse(scores.get(0).passed(), "baseline must expose target bug: " + spec.id());
                    } else if ("pass_to_pass".equals(name)) {
                        assertTrue(scores.get(0).passed(), "baseline regression failed: " + spec.id());
                    }
                }
                assertEquals(Set.of("fail_to_pass", "pass_to_pass"), graderNames,
                        "swe-lite cases require both hidden test groups: " + spec.id());
            } finally {
                workspace.deleteQuietly();
            }
        }
    }

    private static void assertWorkspaceSourceComplete(CaseSpec spec) throws IOException {
        if (spec.fixtureDir() != null) {
            assertTrue(Files.isDirectory(spec.fixtureDir()), "fixture 不存在: " + spec.id());
            try (var files = Files.walk(spec.fixtureDir())) {
                assertTrue(files.anyMatch(Files::isRegularFile), "fixture 不能为空: " + spec.id());
            }
        }
        if (spec.gitSource() != null) {
            assertFalse(spec.gitSource().repo().isBlank(), "git repo 不能为空: " + spec.id());
            assertFalse(spec.gitSource().commit() == null || spec.gitSource().commit().isBlank(),
                    "git workspace 必须固定 commit: " + spec.id());
        }
    }

    private static void assertReferencedCaseFilesExist(CaseSpec spec) {
        for (GraderSpec grader : spec.graders()) {
            JsonNode files = grader.params().path("copy_from_case");
            if (!files.isArray()) {
                continue;
            }
            for (JsonNode file : files) {
                Path referenced = spec.caseDir().resolve(file.asText()).normalize();
                assertTrue(referenced.startsWith(spec.caseDir().normalize()),
                        "copy_from_case 不能逃逸 case 目录: " + spec.id());
                assertTrue(Files.isRegularFile(referenced),
                        "隐藏验收文件不存在: " + spec.id() + " -> " + file.asText());
            }
        }
    }
}
