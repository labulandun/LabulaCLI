package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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
