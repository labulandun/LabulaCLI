package com.paicli.eval;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 被测 PaiCLI 子进程的生命周期：按 {@link SandboxExecutor} 给出的命令启动，
 * 等端口就绪，结束时停止并做沙盒清理。
 */
public final class PaiCliProcess implements AutoCloseable {

    private static final int READY_TIMEOUT_SECONDS = 90;

    private final Process process;
    private final Path logFile;
    private final SandboxExecutor sandbox;
    private boolean stopped;

    private PaiCliProcess(Process process, Path logFile, SandboxExecutor sandbox) {
        this.process = process;
        this.logFile = logFile;
        this.sandbox = sandbox;
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static PaiCliProcess start(SandboxExecutor sandbox, LaunchSpec spec, Path logFile) throws IOException {
        Files.createDirectories(spec.outDir());
        Files.createDirectories(spec.runtimeDir());
        Files.createDirectories(spec.auditDir());
        Files.createDirectories(logFile.getParent());

        SandboxExecutor.Launch launch = sandbox.prepare(spec);
        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.directory(spec.workspace().toFile());
        // .env 条目只补缺不覆盖：用户在真实环境显式 export 的值优先
        for (Map.Entry<String, String> entry : spec.dotEnv().entrySet()) {
            builder.environment().putIfAbsent(entry.getKey(), entry.getValue());
        }
        builder.environment().putAll(launch.env());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Process process = builder.start();
        PaiCliProcess handle = new PaiCliProcess(process, logFile, sandbox);
        try {
            handle.waitUntilReady(spec.port());
        } catch (IOException e) {
            handle.stop();
            throw e;
        }
        return handle;
    }

    private void waitUntilReady(int port) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("被测进程启动即退出 (sandbox=" + sandbox.name()
                        + ", exit=" + process.exitValue() + ")\n" + logTail());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                sleep(500);
            }
        }
        throw new IOException("被测进程 " + READY_TIMEOUT_SECONDS + "s 内未监听端口 " + port
                + " (sandbox=" + sandbox.name() + ")\n" + logTail());
    }

    public String logTail() {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 20);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "(日志读取失败: " + e.getMessage() + ")";
        }
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            sandbox.cleanup();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
