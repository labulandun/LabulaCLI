package com.paicli.eval;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L0 沙盒：直接在宿主机跑 `java -jar paicli.jar serve --http`，cwd 指向一次性工作区。
 * 隔离手段只有 PathGuard / CommandGuard / 一次性工作区，适合常规编码任务。
 */
public final class HostExecutor implements SandboxExecutor {

    @Override
    public String name() {
        return "host";
    }

    @Override
    public Launch prepare(LaunchSpec spec) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> command = List.of(
                javaBin, "-jar", spec.jar().toAbsolutePath().toString(),
                "serve", "--http", "--port", String.valueOf(spec.port()));

        Map<String, String> env = new LinkedHashMap<>(evalEnv(spec));
        // 宿主机模式绑定回环即可，不需要放开监听地址
        env.put("PAICLI_RUNTIME_DIR", spec.runtimeDir().toAbsolutePath().toString());
        env.put("PAICLI_AUDIT_DIR", spec.auditDir().toAbsolutePath().toString());
        env.put("PAICLI_OTEL_TRACES_FILE", spec.spansFile().toAbsolutePath().toString());
        return new Launch(command, env);
    }

    /** 两种沙盒共用的评测环境变量（路径由各自的执行器补齐）。 */
    static Map<String, String> evalEnv(LaunchSpec spec) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PAICLI_RUNTIME_API_KEY", spec.apiKey());
        // 评测强制 file exporter（覆盖 .env 的 OTLP 配置）：span 同步落盘供工件聚合，
        // 评测流量不进 Langfuse
        env.put("PAICLI_OTEL_ENABLED", "true");
        env.put("PAICLI_OTEL_TRACES_EXPORTER", "file");
        return env;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
