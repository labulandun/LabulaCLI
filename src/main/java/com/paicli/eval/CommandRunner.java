package com.paicli.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
            return execute(new ProcessBuilder(command(workspace, command)), timeoutSeconds);
        }

        List<String> command(Path workspace, String command) {
            return new ArrayList<>(List.of(
                    "docker", "run", "--rm",
                    "--memory=2g", "--cpus=2", "--pids-limit=256",
                    "--cap-drop=ALL", "--security-opt", "no-new-privileges",
                    "-v", DockerExecutor.mount(workspace) + ":" + DockerExecutor.WORK_DIR,
                    "-w", DockerExecutor.WORK_DIR,
                    image,
                    "sh", "-lc", command));
        }

        @Override
        public String describe() {
            return "docker:" + image;
        }
    }

    private static Result execute(ProcessBuilder builder, int timeoutSeconds)
            throws IOException, InterruptedException {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds 必须大于 0");
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        Thread outputDrainer = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                stream.transferTo(buffer);
            } catch (IOException e) {
                outputFailure.set(e);
            }
        }, "paicli-command-output-" + process.pid());
        outputDrainer.setDaemon(true);
        outputDrainer.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                stopProcessTree(process);
            }
            awaitOutput(outputDrainer, process);
        } catch (InterruptedException e) {
            stopProcessTree(process);
            Thread.currentThread().interrupt();
            throw e;
        }

        IOException readError = outputFailure.get();
        if (finished && readError != null) {
            throw readError;
        }
        String output = buffer.toString(Charset.defaultCharset());
        return finished
                ? new Result(process.exitValue(), output, false)
                : new Result(-1, output, true);
    }

    private static void stopProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle child = descendants.get(i);
            if (child.isAlive()) {
                child.destroy();
            }
        }
        if (process.isAlive()) {
            process.destroy();
        }
        try {
            process.waitFor(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle child = descendants.get(i);
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 进程结束时管道通常已经关闭
        }
    }

    private static void awaitOutput(Thread outputDrainer, Process process) throws InterruptedException {
        outputDrainer.join(2_000);
        if (!outputDrainer.isAlive()) {
            return;
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // 关闭管道只是为了唤醒仍在读取的后台线程
        }
        outputDrainer.join(500);
    }

    static CommandRunner forCase(CaseSpec spec) {
        return "docker".equals(spec.sandbox()) ? new Docker(spec.dockerImage()) : new Host();
    }
}
