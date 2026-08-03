package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void hostRunnerCapturesOutput() throws Exception {
        String command = isWindows() ? "echo eval-ok" : "printf 'eval-ok\n'";
        CommandRunner.Result result = new CommandRunner.Host().run(workspace, command, 5);

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.output().contains("eval-ok"));
    }

    @Test
    void timeoutIsEnforcedWhileChildKeepsOutputPipeOpen() throws Exception {
        String command = isWindows()
                ? "echo started && ping 127.0.0.1 -n 8 > nul"
                : "printf 'started\n'; sleep 7";
        Instant started = Instant.now();

        CommandRunner.Result result = new CommandRunner.Host().run(workspace, command, 1);
        Duration elapsed = Duration.between(started, Instant.now());

        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertTrue(result.output().contains("started"));
        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "1 秒超时不应等待子命令自然退出，实际耗时=" + elapsed);
    }

    @Test
    void dockerRunnerUsesResourceAndPrivilegeLimits() {
        List<String> command = new CommandRunner.Docker("eval-image:test")
                .command(workspace, "echo ok");
        String joined = String.join(" ", command);

        assertEquals("docker", command.get(0));
        assertTrue(joined.contains("--rm"));
        assertTrue(joined.contains("--memory=2g"));
        assertTrue(joined.contains("--cpus=2"));
        assertTrue(joined.contains("--pids-limit=256"));
        assertTrue(joined.contains("--cap-drop=ALL"));
        assertTrue(joined.contains("--security-opt no-new-privileges"));
        assertTrue(joined.contains(":" + DockerExecutor.WORK_DIR));
        assertTrue(joined.contains("eval-image:test sh -lc echo ok"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
