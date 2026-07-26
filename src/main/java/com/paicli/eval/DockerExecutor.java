package com.paicli.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * L1 沙盒：在 Docker 容器里跑被测 PaiCLI，用于会真实触发破坏性副作用的用例。
 *
 * <p><b>关于网络</b>：容器必须能出网，因为 Agent 要调用 LLM API——没有网络就没有 Agent，
 * 也就无从评测。设计稿里写的 {@code --network=none} 在"跑真实 Agent"的前提下不成立。
 * 因此本沙盒提供的隔离是<b>文件系统、进程与资源</b>层面的，不是网络层面的：容器只能看到
 * 挂载进去的工作区，删不掉宿主机文件，也吃不满宿主机 CPU / 内存 / 进程数。</p>
 *
 * <p>挂载布局刻意把工作区和产物目录分开：{@code /work} 是 Agent 的项目根，
 * {@code /out} 在项目根之外，PathGuard 正常工作时 Agent 碰不到它。</p>
 */
public final class DockerExecutor implements SandboxExecutor {

    static final String WORK_DIR = "/work";
    static final String OUT_DIR = "/out";
    static final String JAR_PATH = "/opt/paicli.jar";
    static final String DEFAULT_IMAGE = "eclipse-temurin:17-jdk";

    private final String image;
    private final String containerName;

    public DockerExecutor(String image) {
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        this.containerName = "paicli-eval-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @Override
    public String name() {
        return "docker";
    }

    String containerName() {
        return containerName;
    }

    @Override
    public Launch prepare(LaunchSpec spec) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--memory=2g", "--cpus=2", "--pids-limit=256",
                "--cap-drop=ALL", "--security-opt", "no-new-privileges",
                "-v", mount(spec.workspace()) + ":" + WORK_DIR,
                "-v", mount(spec.outDir()) + ":" + OUT_DIR,
                "-v", mount(spec.jar()) + ":" + JAR_PATH + ":ro",
                "-w", WORK_DIR,
                "-p", "127.0.0.1:" + spec.port() + ":" + spec.port()));

        Map<String, String> containerEnv = new java.util.LinkedHashMap<>(spec.dotEnv());
        containerEnv.putAll(HostExecutor.evalEnv(spec));
        // 端口映射的包到达容器 eth0 而不是 loopback，必须放开监听地址
        containerEnv.put("PAICLI_RUNTIME_API_HOST", "0.0.0.0");
        containerEnv.put("PAICLI_RUNTIME_DIR", OUT_DIR + "/runtime");
        containerEnv.put("PAICLI_AUDIT_DIR", OUT_DIR + "/audit");
        containerEnv.put("PAICLI_OTEL_TRACES_FILE", OUT_DIR + "/spans.jsonl");
        containerEnv.forEach((key, value) -> {
            command.add("-e");
            command.add(key + "=" + value);
        });

        command.add(image);
        command.addAll(List.of("java", "-jar", JAR_PATH,
                "serve", "--http", "--port", String.valueOf(spec.port())));
        return new Launch(command, Map.of());
    }

    /** 杀掉 `docker run` 客户端进程不保证容器停止，必须显式删。 */
    @Override
    public void cleanup() {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            System.err.println("⚠️ 容器清理失败 " + containerName + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Docker Desktop 接受正斜杠形式的 Windows 路径；反斜杠会被当成转义。 */
    static String mount(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** `docker info` 能跑通才认为可用；不可用时 safety/terminal 用例应 skip 而不是降级到宿主机。 */
    public static Availability probe() {
        try {
            ProcessBuilder builder = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                stream.transferTo(buffer);
                output = buffer.toString(Charset.defaultCharset()).strip();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Availability(false, "docker info 超时");
            }
            if (process.exitValue() != 0) {
                return new Availability(false, "docker info 失败: " + tail(output));
            }
            return new Availability(true, "docker " + output);
        } catch (IOException e) {
            return new Availability(false, "docker 命令不可用: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Availability(false, "docker 探测被中断");
        }
    }

    public record Availability(boolean available, String detail) {
    }

    private static String tail(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(trimmed.length() - 200);
    }
}
