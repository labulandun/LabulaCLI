package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 在工作区执行验收命令（如编译 + 运行检查程序），按退出码打分。
 * 参数：cmd（必填）、expect_exit（默认 0）、timeout_seconds（默认 300）、name（默认 tests_pass）、
 * copy_from_case（打分前从 case 目录注入的文件列表）。
 *
 * <p>执行环境由 {@link CommandRunner} 决定：host 用例在宿主机跑，
 * docker 用例另起一个同镜像的一次性容器跑，保证验收看到的环境和 Agent 当时一致。</p>
 *
 * <p>{@code copy_from_case} 对应 SWE-bench 的 test patch：验证用的测试留在 case 目录、
 * 不进工作区，Agent 全程看不到，避免它照着断言硬编码。打分前才注入。</p>
 */
public final class CommandGrader implements Grader {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int COMMENT_LIMIT = 2000;

    private final GraderSpec graderSpec;
    private final CommandRunner runner;

    public CommandGrader(GraderSpec graderSpec, CommandRunner runner) {
        this.graderSpec = graderSpec;
        this.runner = runner;
    }

    @Override
    public List<Score> grade(CaseSpec spec, RunArtifact artifact, Path workspace) throws Exception {
        String cmd = graderSpec.text("cmd", "");
        if (cmd.isBlank()) {
            throw new IllegalArgumentException("command grader 缺少 cmd (case " + spec.id() + ")");
        }
        String scoreName = graderSpec.text("name", "tests_pass");
        int expectExit = graderSpec.intValue("expect_exit", 0);
        int timeoutSeconds = graderSpec.intValue("timeout_seconds", DEFAULT_TIMEOUT_SECONDS);

        injectCaseFiles(spec, workspace);
        CommandRunner.Result result = runner.run(workspace, cmd, timeoutSeconds);
        if (result.timedOut()) {
            return List.of(Score.bool(scoreName, false,
                    "验收命令超时 (" + timeoutSeconds + "s, " + runner.describe() + "): " + cmd));
        }
        boolean pass = result.exitCode() == expectExit;
        String comment = "exit=" + result.exitCode() + " @" + runner.describe()
                + " cmd=" + cmd + "\n" + tail(result.output());
        return List.of(Score.bool(scoreName, pass, comment));
    }

    /** 把 case 目录下的隐藏测试拷进工作区，保持相对路径。Agent 已经跑完，注入不影响它的行为。 */
    private void injectCaseFiles(CaseSpec spec, Path workspace) throws IOException {
        JsonNode files = graderSpec.params().path("copy_from_case");
        if (!files.isArray()) {
            return;
        }
        for (JsonNode node : files) {
            String relative = node.asText();
            if (relative.isBlank()) {
                continue;
            }
            Path source = spec.caseDir().resolve(relative).normalize();
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("copy_from_case 文件不存在: " + source);
            }
            Path target = workspace.resolve(relative).normalize();
            if (!target.startsWith(workspace)) {
                throw new IllegalArgumentException("copy_from_case 目标逃逸工作区: " + relative);
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= COMMENT_LIMIT
                ? trimmed
                : trimmed.substring(trimmed.length() - COMMENT_LIMIT);
    }
}
