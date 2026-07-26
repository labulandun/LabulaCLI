package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条评测用例，对应 eval/suites/<suite>/<case-id>/case.json。
 * schema 见 docs/evaluation-system-design.md §2.3；E1 只使用其中 host 沙盒相关字段。
 */
public record CaseSpec(String id,
                       String suite,
                       String prompt,
                       int timeoutSeconds,
                       int runs,
                       String sandbox,
                       String dockerImage,
                       Path caseDir,
                       Path fixtureDir,
                       List<GraderSpec> graders) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    public static CaseSpec parse(Path caseDir, String suite, JsonNode root) {
        String id = root.path("id").asText("");
        if (id.isBlank()) {
            throw new IllegalArgumentException("case.json 缺少 id: " + caseDir);
        }
        String prompt = root.path("prompt").asText("");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("case.json 缺少 prompt: " + id);
        }
        JsonNode graderArray = root.path("graders");
        if (!graderArray.isArray() || graderArray.isEmpty()) {
            throw new IllegalArgumentException("case.json 缺少非空 graders: " + id);
        }
        List<GraderSpec> graders = new ArrayList<>();
        for (JsonNode node : graderArray) {
            String type = node.path("type").asText("");
            if (type.isBlank()) {
                throw new IllegalArgumentException("grader 缺少 type: " + id);
            }
            graders.add(new GraderSpec(type, node));
        }
        Path fixtureDir = null;
        String fixture = root.path("workspace").path("fixture").asText("");
        if (!fixture.isBlank()) {
            fixtureDir = caseDir.resolve(fixture).normalize();
            if (!Files.isDirectory(fixtureDir)) {
                throw new IllegalArgumentException("fixture 目录不存在: " + fixtureDir + " (case " + id + ")");
            }
        }
        return new CaseSpec(
                id,
                root.path("suite").asText(suite),
                prompt,
                root.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS),
                Math.max(1, root.path("runs").asInt(1)),
                root.path("sandbox").asText("host"),
                root.path("docker_image").asText(null),
                caseDir,
                fixtureDir,
                List.copyOf(graders));
    }
}
