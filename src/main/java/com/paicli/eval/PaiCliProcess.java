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
 * 被测 PaiCLI 子进程：`java -jar paicli.jar serve --http --port N`，cwd 指向评测工作区。
 * Runtime 数据目录用 PAICLI_RUNTIME_DIR 重定向到本次运行目录，避免污染 ~/.paicli。
 */
public final class PaiCliProcess implements AutoCloseable {

    private static final int READY_TIMEOUT_SECONDS = 60;

    private final Process process;
    private final Path logFile;

    private PaiCliProcess(Process process, Path logFile) {
        this.process = process;
        this.logFile = logFile;
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static PaiCliProcess start(Path jar,
                                      Path workspace,
                                      Path runtimeDir,
                                      Path logFile,
                                      Path spansFile,
                                      int port,
                                      String apiKey,
                                      Map<String, String> dotEnv) throws IOException {
        Files.createDirectories(runtimeDir);
        Files.createDirectories(logFile.getParent());
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(
                javaBin, "-jar", jar.toAbsolutePath().toString(),
                "serve", "--http", "--port", String.valueOf(port)));
        builder.directory(workspace.toFile());
        // .env 条目只补缺不覆盖：用户在真实环境显式 export 的值优先
        for (Map.Entry<String, String> entry : dotEnv.entrySet()) {
            builder.environment().putIfAbsent(entry.getKey(), entry.getValue());
        }
        builder.environment().put("PAICLI_RUNTIME_API_KEY", apiKey);
        builder.environment().put("PAICLI_RUNTIME_DIR", runtimeDir.toAbsolutePath().toString());
        // 评测强制 file exporter（覆盖 .env 的 OTLP 配置）：span 同步落盘供工件聚合，
        // 评测流量不进 Langfuse
        builder.environment().put("PAICLI_OTEL_ENABLED", "true");
        builder.environment().put("PAICLI_OTEL_TRACES_EXPORTER", "file");
        builder.environment().put("PAICLI_OTEL_TRACES_FILE", spansFile.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        PaiCliProcess handle = new PaiCliProcess(process, logFile);
        handle.waitUntilReady(port);
        return handle;
    }

    private void waitUntilReady(int port) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("PaiCLI 子进程启动即退出 (exit=" + process.exitValue() + ")\n" + logTail());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException ignored) {
                sleep(500);
            }
        }
        stop();
        throw new IOException("PaiCLI 子进程 " + READY_TIMEOUT_SECONDS + "s 内未监听端口 " + port + "\n" + logTail());
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
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public void close() {
        stop();
    }
}
