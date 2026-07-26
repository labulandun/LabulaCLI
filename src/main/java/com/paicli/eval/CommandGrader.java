package com.paicli.eval;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 在工作区执行验收命令（如编译 + 运行检查程序），按退出码打分。
 * 参数：cmd（必填）、expect_exit（默认 0）、timeout_seconds（默认 300）、name（默认 tests_pass）。
 */
public final class CommandGrader implements Grader {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int COMMENT_LIMIT = 2000;

    private final GraderSpec graderSpec;

    public CommandGrader(GraderSpec graderSpec) {
        this.graderSpec = graderSpec;
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

        ProcessBuilder builder = new ProcessBuilder(shellCommand(cmd));
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            stream.transferTo(buffer);
            output = buffer.toString(Charset.defaultCharset());
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return List.of(Score.bool(scoreName, false,
                    "验收命令超时 (" + timeoutSeconds + "s): " + cmd));
        }
        boolean pass = process.exitValue() == expectExit;
        String comment = "exit=" + process.exitValue() + " cmd=" + cmd + "\n" + tail(output);
        return List.of(Score.bool(scoreName, pass, comment));
    }

    static List<String> shellCommand(String cmd) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/c", cmd) : List.of("sh", "-lc", cmd);
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= COMMENT_LIMIT
                ? trimmed
                : trimmed.substring(trimmed.length() - COMMENT_LIMIT);
    }
}
