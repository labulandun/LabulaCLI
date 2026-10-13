package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxExecutorTest {

    @TempDir
    Path tempDir;

    private LaunchSpec spec() {
        Path out = tempDir.resolve("out");
        return new LaunchSpec(
                tempDir.resolve("paicli.jar"),
                tempDir.resolve("workspace"),
                out,
                out.resolve("spans.jsonl"),
                out.resolve("runtime"),
                out.resolve("audit"),
                18080,
                "eval-key",
                Map.of("GLM_API_KEY", "secret-value"));
    }

    @Test
    void hostLaunchUsesLocalJvmAndAbsoluteArtifactPaths() {
        SandboxExecutor.Launch launch = new HostExecutor().prepare(spec());

        assertTrue(launch.command().get(0).contains("java"));
        assertEquals(List.of("-jar"), launch.command().subList(1, 2));
        assertTrue(launch.command().containsAll(List.of("serve", "--http", "--port", "18080")));

        Map<String, String> env = launch.env();
        assertEquals("eval-key", env.get("PAICLI_RUNTIME_API_KEY"));
        assertEquals("file", env.get("PAICLI_OTEL_TRACES_EXPORTER"));
        assertTrue(Path.of(env.get("PAICLI_AUDIT_DIR")).isAbsolute());
        assertTrue(Path.of(env.get("PAICLI_OTEL_TRACES_FILE")).isAbsolute());
        // 宿主机模式不放开监听地址
        assertFalse(env.containsKey("PAICLI_RUNTIME_API_HOST"));
    }

    @Test
    void dockerLaunchMountsWorkspaceAndOutSeparately() {
        DockerExecutor executor = new DockerExecutor(null);
        List<String> command = executor.prepare(spec()).command();
        String joined = String.join(" ", command);

        assertEquals("docker", command.get(0));
        assertTrue(joined.contains("--rm"));
        assertTrue(joined.contains("--memory=2g"));
        assertTrue(joined.contains("--pids-limit=256"));
        assertTrue(joined.contains("--cap-drop=ALL"));
        assertTrue(joined.contains(DockerExecutor.DEFAULT_IMAGE));
        // 工作区与产物目录必须是两个挂载：/out 在 Agent 的项目根之外
        assertTrue(joined.contains(":" + DockerExecutor.WORK_DIR));
        assertTrue(joined.contains(":" + DockerExecutor.OUT_DIR));
        assertTrue(joined.contains(DockerExecutor.JAR_PATH + ":ro"));
        assertTrue(joined.contains("-p 127.0.0.1:18080:18080"));
        // 容器内产物路径必须指向挂载点，否则 harness 读不到
        assertTrue(joined.contains("PAICLI_OTEL_TRACES_FILE=" + DockerExecutor.OUT_DIR + "/spans.jsonl"));
        assertTrue(joined.contains("PAICLI_AUDIT_DIR=" + DockerExecutor.OUT_DIR + "/audit"));
        // 端口映射的包到达 eth0，必须放开监听地址
        assertTrue(joined.contains("PAICLI_RUNTIME_API_HOST=0.0.0.0"));
        assertTrue(joined.contains("GLM_API_KEY=secret-value"));
    }

    @Test
    void dockerEnvGoesThroughFlagsNotHostProcessEnv() {
        assertTrue(new DockerExecutor("custom:tag").prepare(spec()).env().isEmpty());
        assertTrue(String.join(" ", new DockerExecutor("custom:tag").prepare(spec()).command())
                .contains("custom:tag"));
    }

    @Test
    void windowsPathsAreNormalisedForDockerMounts() {
        String mount = DockerExecutor.mount(Path.of("C:\\Users\\me\\eval\\ws"));
        assertFalse(mount.contains("\\"));
    }

    @Test
    void eachDockerExecutorGetsItsOwnContainerName() {
        assertFalse(new DockerExecutor(null).containerName()
                .equals(new DockerExecutor(null).containerName()));
        assertTrue(new DockerExecutor(null).containerName().startsWith("paicli-eval-"));
    }
}
