package com.paicli.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 验收命令的执行环境。
 *
 * <p>docker 用例需要这层抽象：被测容器带 {@code --rm}，打分时已经不在了，
 * 而 terminal 类任务的验收本身就是「在那个环境里跑一条命令」——只断言文件内容
 * 覆盖不到"构建能不能过""依赖装没装上"。所以打分时另起一个同镜像、同工作区挂载的
 * 一次性容器。</p>
 */
public interface CommandRunner {

    record Result(int exitCode, String output, boolean timedOut) {
    }

    Result run(Path workspace, String command, int timeoutSeconds) throws IOException, InterruptedException;

    /** 写进 score comment，便于分辨命令到底在哪跑的。 */
    String describe();

    /** 宿主机执行：cwd 为工作区。 */
    final class Host implements CommandRunner {

        @Override
        public Result run(Path workspace, String command, int timeoutSeconds)
                throws IOException, InterruptedException {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> shell = windows
                    ? List.of("cmd.exe", "/c", command)
                    : List.of("sh", "-lc", command);
            ProcessBuilder builder = new ProcessBuilder(shell);
            builder.directory(workspace.toFile());
            return execute(builder, timeoutSeconds);
        }

        @Override
        public String describe() {
            return "host";
        }
    }

    /** 一次性容器执行：镜像与工作区挂载和被测进程一致，保证验收看到的是同一个环境。 */
    final class Docker implements CommandRunner {

        private final String image;

        Docker(String image) {
            this.image = image == null || image.isBlank() ? DockerExecutor.DEFAULT_IMAGE : image;
        }

        @Override
        public Result run(Path workspace, String command, int timeoutSeconds)
                throws IOException, InterruptedException {
            List<String> argv = new ArrayList<>(List.of(
                    "docker", "run", "--rm",
                    "--memory=2g", "--cpus=2", "--pids-limit=256",
                    "-v", DockerExecutor.mount(workspace) + ":" + DockerExecutor.WORK_DIR,
                    "-w", DockerExecutor.WORK_DIR,
                    image,
                    "sh", "-lc", command));
            return execute(new ProcessBuilder(argv), timeoutSeconds);
        }

        @Override
        public String describe() {
            return "docker:" + image;
        }
    }

    private static Result execute(ProcessBuilder builder, int timeoutSeconds)
            throws IOException, InterruptedException {
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
            return new Result(-1, output, true);
        }
        return new Result(process.exitValue(), output, false);
    }

    static CommandRunner forCase(CaseSpec spec) {
        return "docker".equals(spec.sandbox()) ? new Docker(spec.dockerImage()) : new Host();
    }
}
