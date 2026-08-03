package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条评测用例，对应 eval/suites/&lt;suite&gt;/&lt;case-id&gt;/case.json。
 * schema 见 docs/evaluation-system-design.md §2.3。
 *
 * @param gitSource 非 null 时从 git 仓库快照建工作区（swe-lite 用），与 fixtureDir 二选一
 * @param setupCmd  provision 之后、Agent 启动之前跑一次，用来保证起点可编译
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
                       GitSource gitSource,
                       String setupCmd,
                       List<GraderSpec> graders) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** @param repo 本地路径或 URL；本地路径优先，评测才能离线且快 */
    public record GitSource(String repo, String commit) {
    }

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

        JsonNode workspaceNode = root.path("workspace");
        Path fixtureDir = null;
        String fixture = workspaceNode.path("fixture").asText("");
        if (!fixture.isBlank()) {
            fixtureDir = caseDir.resolve(fixture).normalize();
            if (!Files.isDirectory(fixtureDir)) {
                throw new IllegalArgumentException("fixture 目录不存在: " + fixtureDir + " (case " + id + ")");
            }
        }
        GitSource gitSource = parseGit(caseDir, id, workspaceNode.path("git"));
        if (fixtureDir != null && gitSource != null) {
            throw new IllegalArgumentException("workspace.fixture 与 workspace.git 只能二选一: " + id);
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
                gitSource,
                root.path("setup_cmd").asText(null),
                List.copyOf(graders));
    }

    private static GitSource parseGit(Path caseDir, String id, JsonNode gitNode) {
        if (gitNode.isMissingNode() || gitNode.isNull()) {
            return null;
        }
        String repo = gitNode.path("repo").asText("");
        if (repo.isBlank()) {
            throw new IllegalArgumentException("workspace.git 缺少 repo: " + id);
        }
        String commit = gitNode.path("commit").asText("").strip();
        if (commit.isBlank()) {
            throw new IllegalArgumentException("workspace.git 必须固定 commit: " + id);
        }
        // 相对路径按 case 目录解析，便于把 mini-repo 放在 suite 里
        String resolved = repo;
        try {
            Path localRepo = caseDir.resolve(repo).normalize();
            if (Files.isDirectory(localRepo)) {
                resolved = localRepo.toString();
            }
        } catch (java.nio.file.InvalidPathException ignored) {
            // A remote URI may not be a valid local path on this platform.
        }
        return new GitSource(resolved, commit);
    }
}
