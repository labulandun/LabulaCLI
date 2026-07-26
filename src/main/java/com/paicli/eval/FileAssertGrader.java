package com.paicli.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 对工作区文件做断言。参数：path（必填，相对工作区）、exists（默认 true）、
 * contains / regex / not_contains（可选），name（默认 file_match）。
 */
public final class FileAssertGrader implements Grader {

    private final GraderSpec graderSpec;

    public FileAssertGrader(GraderSpec graderSpec) {
        this.graderSpec = graderSpec;
    }

    @Override
    public List<Score> grade(CaseSpec spec, RunArtifact artifact, Path workspace) throws Exception {
        String relPath = graderSpec.text("path", "");
        if (relPath.isBlank()) {
            throw new IllegalArgumentException("file_assert grader 缺少 path (case " + spec.id() + ")");
        }
        String scoreName = graderSpec.text("name", "file_match");
        Path file = workspace.resolve(relPath).normalize();
        if (!file.startsWith(workspace)) {
            throw new IllegalArgumentException("file_assert path 逃逸工作区: " + relPath);
        }
        boolean shouldExist = graderSpec.params().path("exists").asBoolean(true);
        boolean exists = Files.isRegularFile(file);
        if (!shouldExist) {
            return List.of(Score.bool(scoreName, !exists,
                    exists ? "文件不应存在但存在: " + relPath : "文件确认不存在: " + relPath));
        }
        if (!exists) {
            return List.of(Score.bool(scoreName, false, "文件不存在: " + relPath));
        }
        boolean hasContentRule = !graderSpec.text("contains", "").isBlank()
                || !graderSpec.text("regex", "").isBlank()
                || !graderSpec.text("not_contains", "").isBlank();
        if (!hasContentRule) {
            return List.of(Score.bool(scoreName, true, "文件存在: " + relPath));
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<String> failures = OutputAssertGrader.check(graderSpec, content);
        return List.of(Score.bool(scoreName, failures.isEmpty(),
                failures.isEmpty() ? "文件断言通过: " + relPath : relPath + ": " + String.join("; ", failures)));
    }
}
