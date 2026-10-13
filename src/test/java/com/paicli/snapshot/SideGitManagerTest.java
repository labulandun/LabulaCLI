package com.paicli.snapshot;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50,
                        List.of(".git", "target", "*.class", "module/*.tmp")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertFalse(Files.exists(project.resolve(".git")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }

    @Test
    void usesBareRepositoryAndCustomRefWithoutTouchingProjectGit() throws Exception {
        Path project = tempDir.resolve("git-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("tracked.txt"), "before");
        try (Git git = Git.init().setDirectory(project.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern("tracked.txt").call();
            git.commit()
                    .setAuthor("Project User", "project@example.test")
                    .setCommitter("Project User", "project@example.test")
                    .setMessage("project commit")
                    .call();
        }

        Path projectGit = project.resolve(".git");
        byte[] headBefore = Files.readAllBytes(projectGit.resolve("HEAD"));
        byte[] configBefore = Files.readAllBytes(projectGit.resolve("config"));
        byte[] indexBefore = Files.readAllBytes(projectGit.resolve("index"));

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of()));
        TurnSnapshot pre = manager.preTurnSnapshot("turn-1", "before task");
        Files.writeString(project.resolve("tracked.txt"), "after");
        manager.postTurnSnapshot("turn-1", "after task");
        RestoreResult restore = manager.restorePreTurn(1);

        assertTrue(restore.success());
        assertTrue(Files.isDirectory(projectGit));
        assertTrue(Arrays.equals(headBefore, Files.readAllBytes(projectGit.resolve("HEAD"))));
        assertTrue(Arrays.equals(configBefore, Files.readAllBytes(projectGit.resolve("config"))));
        assertTrue(Arrays.equals(indexBefore, Files.readAllBytes(projectGit.resolve("index"))));

        try (Repository sideRepository = new FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile())
                .build()) {
            assertTrue(sideRepository.isBare());
            assertNull(sideRepository.resolve(Constants.HEAD));
            assertTrue(sideRepository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).isEmpty());
            assertNotNull(sideRepository.exactRef(SideGitManager.SNAPSHOT_REF));
            assertNull(sideRepository.getConfig().getString("core", null, "worktree"));
            assertFalse(sideRepository.getConfig().getBoolean(
                    "extensions", null, "worktreeConfig", false));
            assertEquals(manager.listSnapshots(1).get(0).commitId(),
                    sideRepository.exactRef(SideGitManager.SNAPSHOT_REF).getObjectId().name());
            assertNotNull(sideRepository.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(pre.commitId())));
        }
    }

    @Test
    void disablesSnapshotsAndRefusesCleanWhenProjectGitPointsIntoSnapshotRoot() throws Exception {
        Path project = tempDir.resolve("legacy-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git"));
        SideGitManager pathCalculator = new SideGitManager(project, config);
        Files.createDirectories(pathCalculator.gitDir());
        Path sentinel = pathCalculator.gitDir().resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");
        Files.writeString(project.resolve(".git"), "gitdir: " + pathCalculator.gitDir());

        SideGitManager manager = new SideGitManager(project, config);

        assertFalse(manager.isEnabled());
        assertTrue(manager.startupWarning().isPresent());
        assertTrue(manager.startupWarning().orElseThrow().contains("快照已禁用"));
        assertNull(manager.preTurnSnapshot("turn-1", "blocked"));
        assertTrue(manager.cleanSnapshots().contains("拒绝清理"));
        assertTrue(Files.exists(sentinel));
    }

    @Test
    void cleanRemovesOnlySideRepositoryAndKeepsProjectGit() throws Exception {
        Path project = tempDir.resolve("clean-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        try (Git ignored = Git.init().setDirectory(project.toFile()).call()) {
            // A real project GitDir must survive Side-Git cleanup.
        }
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));
        manager.preTurnSnapshot("turn-1", "snapshot");

        String result = manager.cleanSnapshots();

        assertTrue(result.contains("已清理"), result);
        assertTrue(Files.isDirectory(project.resolve(".git")));
        assertFalse(Files.exists(manager.gitDir().getParent()));
    }

    @Test
    void keepsExcludedFilesOutsideSnapshotRestore() throws Exception {
        Path project = tempDir.resolve("excluded-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project.resolve("module/target"));
        Files.writeString(project.resolve("source.txt"), "before");
        Files.writeString(project.resolve("module/target/original.txt"), "excluded-before");
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50,
                        List.of(".git", "target", "*.class", "module/*.tmp")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("source.txt"), "after");
        Files.writeString(project.resolve("module/target/original.txt"), "excluded-after");
        Files.writeString(project.resolve("module/target/generated.txt"), "keep me");
        Files.writeString(project.resolve("module/Generated.class"), "keep class");
        Files.writeString(project.resolve("module/generated.tmp"), "keep tmp");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("source.txt")));
        assertEquals("excluded-after", Files.readString(project.resolve("module/target/original.txt")));
        assertEquals("keep me", Files.readString(project.resolve("module/target/generated.txt")));
        assertEquals("keep class", Files.readString(project.resolve("module/Generated.class")));
        assertEquals("keep tmp", Files.readString(project.resolve("module/generated.tmp")));
    }

    @Test
    void migratesLegacyHeadToSnapshotRefWithoutAdvancingHead() throws Exception {
        Path project = tempDir.resolve("legacy-ref-project");
        Path legacyWorktree = tempDir.resolve("legacy-worktree");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.createDirectories(legacyWorktree);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git"));
        SideGitManager manager = new SideGitManager(project, config);

        Files.writeString(legacyWorktree.resolve("legacy.txt"), "legacy");
        String legacyHead;
        try (Git git = Git.init()
                .setGitDir(manager.gitDir().toFile())
                .setDirectory(legacyWorktree.toFile())
                .setInitialBranch("main")
                .call()) {
            git.add().addFilepattern("legacy.txt").call();
            legacyHead = git.commit()
                    .setAuthor("PaiCLI Snapshot", "snapshot@paicli.local")
                    .setCommitter("PaiCLI Snapshot", "snapshot@paicli.local")
                    .setMessage("pre-turn legacy-1")
                    .call()
                    .getId()
                    .name();
        }

        List<TurnSnapshot> migrated = manager.listSnapshots(10);
        Files.writeString(project.resolve("new.txt"), "new snapshot");
        TurnSnapshot created = manager.postTurnSnapshot("turn-2", "new format");

        assertEquals(1, migrated.size());
        assertEquals(legacyHead, migrated.get(0).commitId());
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile())
                .build()) {
            assertTrue(repository.isBare());
            assertEquals(legacyHead, repository.resolve(Constants.HEAD).name());
            assertEquals(created.commitId(), repository.resolve(SideGitManager.SNAPSHOT_REF).name());
            assertNull(repository.getConfig().getString("core", null, "worktree"));
        }
    }

    @Test
    void collectsRequestedNumberOfPreTurnSnapshotsAfterFiltering() throws Exception {
        Path project = tempDir.resolve("pre-turn-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));
        for (int i = 1; i <= 3; i++) {
            Files.writeString(project.resolve("state.txt"), "pre-" + i);
            manager.preTurnSnapshot("turn-" + i, "pre");
            Files.writeString(project.resolve("state.txt"), "post-" + i);
            manager.postTurnSnapshot("turn-" + i, "post");
        }

        List<TurnSnapshot> preTurns = manager.listPreTurnSnapshots(3);

        assertEquals(3, preTurns.size());
        assertEquals("turn-3", preTurns.get(0).turnId());
        assertEquals("turn-1", preTurns.get(2).turnId());
    }

    @Test
    void malformedGitPointerDisablesSnapshotsInsteadOfBreakingStartup() throws Exception {
        Path project = tempDir.resolve("malformed-pointer-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.write(project.resolve(".git"), "gitdir: \0".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));

        assertFalse(manager.isEnabled());
        assertTrue(manager.startupWarning().isPresent());
        assertTrue(manager.cleanSnapshots().contains("清理快照失败"));
    }

    @Test
    void rejectsSnapshotRootRedirectedIntoProjectGit() throws Exception {
        Path project = tempDir.resolve("redirect-project");
        Path snapshots = tempDir.resolve("redirected-snapshots");
        Files.createDirectories(project);
        try (Git ignored = Git.init().setDirectory(project.toFile()).call()) {
            // Initialize the GitDir that the malicious link will target.
        }
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));
        byte[] configBefore = Files.readAllBytes(project.resolve(".git/config"));
        try {
            Files.createSymbolicLink(snapshots, project.resolve(".git"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前平台不能创建测试符号链接");
        }

        assertThrows(java.io.IOException.class,
                () -> manager.preTurnSnapshot("turn-1", "must not write project Git"));
        assertTrue(Arrays.equals(configBefore, Files.readAllBytes(project.resolve(".git/config"))));
    }

    @Test
    void disablesSnapshotRepositoryConfiguredInsideProjectGit() throws Exception {
        Path project = tempDir.resolve("inside-git-project");
        Files.createDirectories(project);
        try (Git ignored = Git.init().setDirectory(project.toFile()).call()) {
            // Initialize a normal project GitDir.
        }
        Path projectGit = project.resolve(".git");
        byte[] configBefore = Files.readAllBytes(projectGit.resolve("config"));

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, projectGit, 50, List.of(".git")));

        assertFalse(manager.isEnabled());
        assertTrue(manager.startupWarning().isPresent());
        assertNull(manager.preTurnSnapshot("turn-1", "blocked"));
        assertTrue(Arrays.equals(configBefore, Files.readAllBytes(projectGit.resolve("config"))));
    }

    @Test
    void restoresSymlinkAndExecutableModeOnPosixFileSystems() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path project = tempDir.resolve("posix-project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Path script = project.resolve("script.sh");
        Path link = project.resolve("script-link");
        Files.writeString(script, "#!/bin/sh\necho before\n");
        Set<PosixFilePermission> executable = EnumSet.copyOf(Files.getPosixFilePermissions(script));
        executable.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, executable);
        Files.createSymbolicLink(link, Path.of("script.sh"));
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git")));
        manager.preTurnSnapshot("turn-1", "posix before");

        Files.writeString(script, "#!/bin/sh\necho after\n");
        Set<PosixFilePermission> nonExecutable = EnumSet.copyOf(Files.getPosixFilePermissions(script));
        nonExecutable.remove(PosixFilePermission.OWNER_EXECUTE);
        nonExecutable.remove(PosixFilePermission.GROUP_EXECUTE);
        nonExecutable.remove(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(script, nonExecutable);
        Files.delete(link);
        Files.writeString(link, "not a link");
        manager.postTurnSnapshot("turn-1", "posix after");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(Path.of("script.sh"), Files.readSymbolicLink(link));
        assertTrue(Files.getPosixFilePermissions(script).contains(PosixFilePermission.OWNER_EXECUTE));
    }
}
