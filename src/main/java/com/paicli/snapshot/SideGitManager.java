package com.paicli.snapshot;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.Paths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class SideGitManager {
    static final String SNAPSHOT_REF = "refs/paicli/snapshots/history";

    private static final PersonIdent SNAPSHOT_IDENT =
            new PersonIdent("PaiCLI Snapshot", "snapshot@paicli.local");
    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path projectRoot;
    private final SnapshotConfig config;
    private final List<FastIgnoreRule> excludeRules;
    private final Path snapshotsRoot;
    private final Path gitDir;
    private final String migrationError;

    public SideGitManager(Path projectRoot) {
        this(projectRoot, SnapshotConfig.fromEnvironment());
    }

    public SideGitManager(Path projectRoot, SnapshotConfig config) {
        this.projectRoot = normalizeProjectRoot(projectRoot);
        SnapshotConfig resolvedConfig = config == null ? SnapshotConfig.fromEnvironment() : config;
        this.snapshotsRoot = resolvedConfig.snapshotsRoot().toAbsolutePath().normalize();
        this.config = new SnapshotConfig(
                resolvedConfig.enabled(),
                snapshotsRoot,
                resolvedConfig.maxSnapshots(),
                List.copyOf(resolvedConfig.excludes()));
        this.excludeRules = this.config.excludes().stream()
                .filter(rule -> rule != null && !rule.isBlank())
                .map(rule -> new FastIgnoreRule(rule.trim().replace('\\', '/')))
                .toList();
        this.gitDir = snapshotsRoot
                .resolve(hash(parentKey(this.projectRoot)))
                .resolve(hash(this.projectRoot.toString()))
                .resolve(".git");
        this.migrationError = detectMigrationError();
    }

    public synchronized TurnSnapshot preTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot postTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.POST_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot preRestoreSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_RESTORE, turnId, summary);
    }

    public synchronized TurnSnapshot createSnapshot(SnapshotPhase phase, String turnId, String summary)
            throws IOException, GitAPIException {
        if (!isEnabled()) {
            return null;
        }
        ensureNoMigrationError();
        String message = phase.label() + " " + safeTurnId(turnId)
                + (summary == null || summary.isBlank() ? "" : "\n\n" + summary.trim());

        try (Repository repository = openRepository(true);
             ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId treeId = writeWorkspaceTree(inserter);
            ObjectId parentId = repository.resolve(SNAPSHOT_REF);
            Instant now = Instant.now();
            PersonIdent ident = new PersonIdent(SNAPSHOT_IDENT, now, ZoneId.systemDefault());

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (parentId != null) {
                commit.setParentId(parentId);
            }
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(message);

            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            updateSnapshotRef(repository, parentId, commitId, ident, message);

            try (RevWalk walk = new RevWalk(repository)) {
                return toSnapshot(walk.parseCommit(commitId))
                        .orElseThrow(() -> new IOException("创建了无法识别的快照提交: " + commitId.name()));
            }
        }
    }

    public synchronized List<TurnSnapshot> listSnapshots(int limit) throws IOException, GitAPIException {
        int max = limit <= 0 ? config.maxSnapshots() : limit;
        return readSnapshots(max, snapshot -> true);
    }

    public synchronized List<TurnSnapshot> listPreTurnSnapshots(int limit) throws IOException, GitAPIException {
        int max = limit <= 0 ? config.maxSnapshots() : limit;
        return readSnapshots(max, snapshot -> snapshot.phase() == SnapshotPhase.PRE_TURN);
    }

    private List<TurnSnapshot> readSnapshots(int max, Predicate<TurnSnapshot> filter) throws IOException {
        ensureNoMigrationError();
        if (!config.enabled() || !isRepositoryInitialized()) {
            return List.of();
        }
        List<TurnSnapshot> snapshots = new ArrayList<>();
        try (Repository repository = openRepository(false)) {
            ObjectId tip = repository.resolve(SNAPSHOT_REF);
            if (tip == null) {
                return List.of();
            }
            try (RevWalk walk = new RevWalk(repository)) {
                walk.markStart(walk.parseCommit(tip));
                for (RevCommit commit : walk) {
                    toSnapshot(commit).filter(filter).ifPresent(snapshots::add);
                    if (snapshots.size() >= max) {
                        break;
                    }
                }
            }
        }
        return snapshots;
    }

    public synchronized RestoreResult restorePreTurn(int offset) throws IOException, GitAPIException {
        if (migrationError != null) {
            return RestoreResult.failure(migrationError);
        }
        if (!config.enabled()) {
            return RestoreResult.failure("快照功能已关闭");
        }
        int normalizedOffset = Math.max(1, offset);
        List<TurnSnapshot> preTurns = listPreTurnSnapshots(Math.max(normalizedOffset, config.maxSnapshots()));
        if (preTurns.size() < normalizedOffset) {
            return RestoreResult.failure("找不到最近第 " + normalizedOffset + " 个 pre-turn 快照");
        }
        TurnSnapshot target = preTurns.get(normalizedOffset - 1);
        TurnSnapshot current = preRestoreSnapshot("restore-" + Instant.now().toEpochMilli(),
                "Before restoring " + target.shortCommitId());
        try (Repository repository = openRepository(false)) {
            Map<String, SnapshotTreeEntry> targetTree = treeEntries(repository, ObjectId.fromString(target.commitId()));
            Map<String, SnapshotTreeEntry> currentTree = current == null
                    ? Map.of()
                    : treeEntries(repository, ObjectId.fromString(current.commitId()));
            List<String> removed = deleteTrackedFilesMissingFromTarget(currentTree, targetTree);
            List<String> restored = writeTargetTree(repository, targetTree);
            return RestoreResult.success(target.commitId(), restored, removed);
        }
    }

    public synchronized String formatStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("📸 Side-Git 快照状态\n");
        if (migrationError != null) {
            sb.append("   状态: 已禁用（迁移保护）\n");
            sb.append("   错误: ").append(migrationError).append('\n');
        } else {
            sb.append("   状态: ").append(config.enabled() ? "启用" : "关闭").append('\n');
        }
        sb.append("   项目根: ").append(projectRoot).append('\n');
        sb.append("   Side-Git: ").append(gitDir).append('\n');
        sb.append("   快照引用: ").append(SNAPSHOT_REF).append('\n');
        sb.append("   最大列表/定位数: ").append(config.maxSnapshots()).append('\n');
        sb.append("   排除: ").append(String.join(", ", config.excludes())).append('\n');
        if (migrationError != null) {
            sb.append("   最近快照: 不可用");
            return sb.toString();
        }
        try {
            List<TurnSnapshot> snapshots = listSnapshots(1);
            if (snapshots.isEmpty()) {
                sb.append("   最近快照: 暂无");
            } else {
                TurnSnapshot latest = snapshots.get(0);
                sb.append("   最近快照: ")
                        .append(latest.phase().label())
                        .append(" ")
                        .append(latest.shortCommitId())
                        .append(" ")
                        .append(latest.createdAt());
            }
        } catch (Exception e) {
            sb.append("   最近快照: 读取失败 - ").append(e.getMessage());
        }
        return sb.toString();
    }

    public synchronized String cleanSnapshots() {
        Path deleteRoot = gitDir.getParent().toAbsolutePath().normalize();
        try {
            Optional<Path> projectGitDir = resolveProjectGitDir();
            if (projectGitDir.isPresent() && pathsOverlap(deleteRoot, projectGitDir.get())) {
                return "❌ 拒绝清理：待删除 Side-Git 目录与项目当前 GitDir 重叠，请先完成仓库迁移";
            }
            if (!isStrictDescendant(deleteRoot, snapshotsRoot)) {
                return "❌ 拒绝清理：Side-Git 目录不在配置的快照根目录内";
            }
            if (!Files.exists(deleteRoot, NO_FOLLOW_LINKS)) {
                return "📥 暂无 Side-Git 快照目录";
            }
            deleteRecursively(deleteRoot);
            return "🧹 已清理当前项目的 Side-Git 快照目录: " + deleteRoot;
        } catch (IOException | RuntimeException e) {
            return "❌ 清理快照失败: " + e.getMessage();
        }
    }

    public boolean isEnabled() {
        return config.enabled() && migrationError == null;
    }

    public Optional<String> startupWarning() {
        return Optional.ofNullable(migrationError);
    }

    public Path gitDir() {
        return gitDir;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public SnapshotConfig config() {
        return config;
    }

    private Repository openRepository(boolean create) throws IOException {
        ensureNoMigrationError();
        ensureSafeSideRepositoryLocation();
        if (create) {
            Files.createDirectories(gitDir);
        } else if (!isRepositoryInitialized()) {
            throw new IOException("Side-Git 快照仓库尚未初始化");
        }
        ensureSafeSideRepositoryLocation();

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setBare()
                .build();
        try {
            if (!isRepositoryInitialized()) {
                repository.create(true);
            }
            ensureBareConfiguration(repository);
            migrateLegacySnapshotRef(repository);
            return repository;
        } catch (Exception e) {
            repository.close();
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("初始化 Side-Git 快照仓库失败", e);
        }
    }

    private boolean isRepositoryInitialized() {
        return Files.isRegularFile(gitDir.resolve("config"), NO_FOLLOW_LINKS)
                && Files.isDirectory(gitDir.resolve("objects"), NO_FOLLOW_LINKS);
    }

    private void ensureBareConfiguration(Repository repository) throws IOException {
        StoredConfig repositoryConfig = repository.getConfig();
        boolean changed = !repositoryConfig.getBoolean("core", null, "bare", false)
                || repositoryConfig.getString("core", null, "worktree") != null
                || repositoryConfig.getBoolean("extensions", null, "worktreeConfig", false);
        if (changed) {
            repositoryConfig.setBoolean("core", null, "bare", true);
            repositoryConfig.unset("core", null, "worktree");
            repositoryConfig.unset("extensions", null, "worktreeConfig");
            repositoryConfig.save();
        }
    }

    private void migrateLegacySnapshotRef(Repository repository) throws IOException {
        if (repository.exactRef(SNAPSHOT_REF) != null) {
            return;
        }
        ObjectId legacyHead = repository.resolve(Constants.HEAD);
        if (legacyHead == null) {
            return;
        }
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(legacyHead));
            for (RevCommit commit : walk) {
                if (toSnapshot(commit).isPresent()) {
                    RefUpdate update = repository.updateRef(SNAPSHOT_REF);
                    update.setExpectedOldObjectId(ObjectId.zeroId());
                    update.setNewObjectId(commit.getId());
                    update.setRefLogMessage("migrate legacy PaiCLI snapshot history", false);
                    RefUpdate.Result result = update.update();
                    if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.NO_CHANGE) {
                        throw new IOException("迁移旧 Side-Git 快照引用失败: " + result);
                    }
                    return;
                }
            }
        }
    }

    private ObjectId writeWorkspaceTree(ObjectInserter inserter) throws IOException {
        if (!Files.isDirectory(projectRoot)) {
            throw new IOException("项目根目录不存在或不是目录: " + projectRoot);
        }
        List<WorkspaceFile> files = collectWorkspaceFiles();
        List<DirCacheEntry> entries = new ArrayList<>(files.size());
        for (WorkspaceFile file : files) {
            ObjectId objectId = insertBlob(inserter, file);
            DirCacheEntry entry = new DirCacheEntry(file.relativePath());
            entry.setFileMode(file.mode());
            entry.setObjectId(objectId);
            entries.add(entry);
        }
        entries.sort(SideGitManager::compareDirCacheEntries);

        DirCache cache = DirCache.newInCore();
        DirCacheBuilder builder = cache.builder();
        for (DirCacheEntry entry : entries) {
            builder.add(entry);
        }
        builder.finish();
        return cache.writeTree(inserter);
    }

    private List<WorkspaceFile> collectWorkspaceFiles() throws IOException {
        List<WorkspaceFile> files = new ArrayList<>();
        Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(projectRoot) && shouldSkipWorkspacePath(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!shouldSkipWorkspacePath(file) && (attrs.isRegularFile() || attrs.isSymbolicLink())) {
                            String relative = relativePath(file);
                            files.add(new WorkspaceFile(file, relative, fileMode(file, attrs)));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return files;
    }

    private ObjectId insertBlob(ObjectInserter inserter, WorkspaceFile file) throws IOException {
        if (file.mode() == FileMode.SYMLINK) {
            byte[] target = Files.readSymbolicLink(file.path()).toString().getBytes(StandardCharsets.UTF_8);
            return inserter.insert(Constants.OBJ_BLOB, target);
        }
        long size = Files.size(file.path());
        try (InputStream input = Files.newInputStream(file.path())) {
            return inserter.insert(Constants.OBJ_BLOB, size, input);
        }
    }

    private void updateSnapshotRef(Repository repository,
                                   ObjectId parentId,
                                   ObjectId commitId,
                                   PersonIdent ident,
                                   String message) throws IOException {
        RefUpdate update = repository.updateRef(SNAPSHOT_REF);
        update.setExpectedOldObjectId(parentId == null ? ObjectId.zeroId() : parentId);
        update.setNewObjectId(commitId);
        update.setRefLogIdent(ident);
        update.setRefLogMessage(message, false);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("更新 Side-Git 快照引用失败: " + result);
        }
    }

    private Optional<TurnSnapshot> toSnapshot(RevCommit commit) {
        String firstLine = Optional.ofNullable(commit.getShortMessage()).orElse("");
        String[] parts = firstLine.split("\\s+", 2);
        Optional<SnapshotPhase> phase = parsePhase(parts.length > 0 ? parts[0] : "");
        if (phase.isEmpty()) {
            return Optional.empty();
        }
        String turnId = parts.length > 1 ? parts[1] : "";
        return Optional.of(new TurnSnapshot(
                commit.getId().name(),
                phase.get(),
                turnId,
                Instant.ofEpochSecond(commit.getCommitTime()),
                firstLine
        ));
    }

    private Optional<SnapshotPhase> parsePhase(String value) {
        for (SnapshotPhase phase : SnapshotPhase.values()) {
            if (phase.label().equals(value)) {
                return Optional.of(phase);
            }
        }
        return Optional.empty();
    }

    private Map<String, SnapshotTreeEntry> treeEntries(Repository repository, ObjectId commitId) throws IOException {
        Map<String, SnapshotTreeEntry> entries = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (!isExcluded(path)) {
                        entries.put(path, new SnapshotTreeEntry(
                                treeWalk.getObjectId(0), treeWalk.getFileMode(0)));
                    }
                }
            }
        }
        return entries;
    }

    private List<String> deleteTrackedFilesMissingFromTarget(Map<String, SnapshotTreeEntry> currentTree,
                                                             Map<String, SnapshotTreeEntry> targetTree) throws IOException {
        List<String> removed = new ArrayList<>();
        for (String path : currentTree.keySet()) {
            if (targetTree.containsKey(path) || isExcluded(path)) {
                continue;
            }
            Path file = resolveWorkspacePath(path);
            ensureNoSymlinkParents(file);
            if (!Files.exists(file, NO_FOLLOW_LINKS)
                    || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.deleteIfExists(file);
            removed.add(path);
            pruneEmptyParents(file.getParent());
        }
        return removed;
    }

    private List<String> writeTargetTree(Repository repository,
                                         Map<String, SnapshotTreeEntry> targetTree) throws IOException {
        List<String> restored = new ArrayList<>();
        for (Map.Entry<String, SnapshotTreeEntry> entry : targetTree.entrySet()) {
            String path = entry.getKey();
            if (isExcluded(path)) {
                continue;
            }
            SnapshotTreeEntry treeEntry = entry.getValue();
            if (treeEntry.mode() == FileMode.GITLINK || treeEntry.mode() == FileMode.TREE) {
                continue;
            }
            Path file = resolveWorkspacePath(path);
            ensureSafeParentDirectories(file);
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("无法用快照文件覆盖目录: " + path);
            }

            ObjectLoader loader = repository.open(treeEntry.objectId());
            if (treeEntry.mode() == FileMode.SYMLINK) {
                Files.deleteIfExists(file);
                String target = new String(loader.getBytes(), StandardCharsets.UTF_8);
                Files.createSymbolicLink(file, Path.of(target));
            } else {
                if (Files.isSymbolicLink(file)) {
                    Files.delete(file);
                }
                ensureNoSymlinkParents(file);
                try (OutputStream output = Files.newOutputStream(file)) {
                    loader.copyTo(output);
                }
                restoreExecutableMode(file, treeEntry.mode() == FileMode.EXECUTABLE_FILE);
            }
            restored.add(path);
        }
        return restored;
    }

    private Path resolveWorkspacePath(String relative) throws IOException {
        Path path = projectRoot.resolve(relative).normalize();
        if (!path.startsWith(projectRoot)) {
            throw new IOException("快照路径逃逸项目根目录: " + relative);
        }
        return path;
    }

    private void ensureSafeParentDirectories(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null || parent.equals(projectRoot)) {
            return;
        }
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.exists(current, NO_FOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("拒绝通过符号链接父目录恢复快照: " + relativePath(current));
                }
                ensureResolvedInsideProject(current);
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("快照目标父路径不是目录: " + relativePath(current));
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void ensureNoSymlinkParents(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null || parent.equals(projectRoot)) {
            return;
        }
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(parent)) {
            current = current.resolve(segment);
            if (!Files.exists(current, NO_FOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(current)) {
                throw new IOException("拒绝通过符号链接父目录恢复快照: " + relativePath(current));
            }
            ensureResolvedInsideProject(current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("快照目标父路径不是目录: " + relativePath(current));
            }
        }
    }

    private void ensureResolvedInsideProject(Path path) throws IOException {
        Path resolvedProjectRoot = normalizeForComparison(projectRoot);
        Path resolvedPath = normalizeForComparison(path);
        if (!resolvedPath.startsWith(resolvedProjectRoot)) {
            throw new IOException("快照目标父路径解析到项目根之外: " + relativePath(path));
        }
    }

    private void restoreExecutableMode(Path file, boolean executable) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            Set<PosixFilePermission> updated = EnumSet.noneOf(PosixFilePermission.class);
            updated.addAll(permissions);
            Set<PosixFilePermission> executePermissions = Set.of(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE);
            if (executable) {
                updated.addAll(executePermissions);
            } else {
                updated.removeAll(executePermissions);
            }
            Files.setPosixFilePermissions(file, updated);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等不支持 POSIX 权限的平台无需额外处理。
        }
    }

    private void pruneEmptyParents(Path dir) throws IOException {
        Path current = dir;
        while (current != null && current.startsWith(projectRoot) && !current.equals(projectRoot)) {
            try (var stream = Files.list(current)) {
                if (stream.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private boolean shouldSkipWorkspacePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path projectGitMarker = projectRoot.resolve(".git").toAbsolutePath().normalize();
        Path sideRepositoryRoot = gitDir.getParent().toAbsolutePath().normalize();
        return normalized.equals(projectGitMarker)
                || normalized.startsWith(projectGitMarker)
                || normalized.startsWith(sideRepositoryRoot)
                || isExcluded(relativePath(path), Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }

    private boolean isExcluded(String relative) {
        return isExcluded(relative, false);
    }

    private boolean isExcluded(String relative, boolean directory) {
        String normalized = relative.replace('\\', '/');
        if (isGitMetadataPath(normalized)) {
            return true;
        }
        Boolean excluded = null;
        for (FastIgnoreRule rule : excludeRules) {
            if (rule.isMatch(normalized, directory, true)) {
                excluded = rule.getResult();
            }
        }
        return Boolean.TRUE.equals(excluded);
    }

    private static boolean isGitMetadataPath(String normalized) {
        for (String segment : normalized.split("/")) {
            if (".git".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String detectMigrationError() {
        try {
            Optional<Path> projectGitDir = resolveProjectGitDir();
            if (projectGitDir.isEmpty()) {
                return null;
            }
            Path resolvedGitDir = projectGitDir.get();
            Path defaultSnapshotsRoot = Path.of(
                    System.getProperty("user.home"), ".paicli", "snapshots");
            if (pathsOverlap(resolvedGitDir, gitDir)
                    || isManagedSnapshotGitDir(resolvedGitDir, snapshotsRoot)
                    || isManagedSnapshotGitDir(resolvedGitDir, defaultSnapshotsRoot)) {
                return "检测到项目 GitDir 位于 PaiCLI 快照目录 " + resolvedGitDir
                        + "；快照已禁用，请先恢复项目 .git 后再启用";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "无法安全解析项目 .git（" + e.getMessage() + "）；快照已禁用";
        }
    }

    private void ensureSafeSideRepositoryLocation() throws IOException {
        try {
            Path resolvedSnapshotsRoot = normalizeForComparison(snapshotsRoot);
            Path resolvedSideGitDir = normalizeForComparison(gitDir);
            if (!resolvedSideGitDir.startsWith(resolvedSnapshotsRoot)
                    || resolvedSideGitDir.equals(resolvedSnapshotsRoot)) {
                throw new IOException("Side-Git 路径解析到配置的快照根目录之外: " + resolvedSideGitDir);
            }
            Optional<Path> projectGitDir = resolveProjectGitDir();
            if (projectGitDir.isPresent() && pathsOverlap(resolvedSideGitDir, projectGitDir.get())) {
                throw new IOException("Side-Git 路径与项目当前 GitDir 重叠，已拒绝写入");
            }
        } catch (RuntimeException e) {
            throw new IOException("无法安全验证 Side-Git 路径: " + e.getMessage(), e);
        }
    }

    private static boolean isManagedSnapshotGitDir(Path candidate, Path root) {
        Path resolvedCandidate = normalizeForComparison(candidate);
        Path resolvedRoot = normalizeForComparison(root);
        if (!resolvedCandidate.startsWith(resolvedRoot)) {
            return false;
        }
        Path relative = resolvedRoot.relativize(resolvedCandidate);
        return relative.getNameCount() == 3
                && isShortHash(relative.getName(0).toString())
                && isShortHash(relative.getName(1).toString())
                && ".git".equals(relative.getName(2).toString());
    }

    private static boolean isShortHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{16}");
    }

    private Optional<Path> resolveProjectGitDir() throws IOException {
        Path dotGit = projectRoot.resolve(".git");
        if (Files.exists(dotGit, NO_FOLLOW_LINKS)) {
            return resolveExplicitProjectGitDir(dotGit);
        }
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(projectRoot.toFile());
        return builder.getGitDir() == null
                ? Optional.empty()
                : Optional.of(normalizeForComparison(builder.getGitDir().toPath()));
    }

    private Optional<Path> resolveExplicitProjectGitDir(Path dotGit) throws IOException {
        if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(normalizeForComparison(dotGit));
        }
        if (Files.isSymbolicLink(dotGit)) {
            Path target = dotGit.toRealPath();
            if (Files.isDirectory(target)) {
                return Optional.of(normalizeForComparison(target));
            }
            if (Files.isRegularFile(target)) {
                return Optional.of(parseGitDirPointer(target, dotGit.getParent()));
            }
            throw new IOException(".git 符号链接目标不是文件或目录");
        }
        if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(parseGitDirPointer(dotGit, dotGit.getParent()));
        }
        throw new IOException(".git 不是文件、目录或符号链接");
    }

    private Path parseGitDirPointer(Path pointerFile, Path relativeBase) throws IOException {
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(pointerFile, StandardCharsets.UTF_8)) {
            firstLine = reader.readLine();
        }
        if (firstLine == null || !firstLine.regionMatches(true, 0, "gitdir:", 0, "gitdir:".length())) {
            throw new IOException(".git 指针缺少 gitdir: 前缀");
        }
        String value = firstLine.substring("gitdir:".length()).trim();
        if (value.isEmpty()) {
            throw new IOException(".git 指针目标为空");
        }
        Path target = Path.of(value);
        if (!target.isAbsolute()) {
            target = relativeBase.resolve(target);
        }
        return normalizeForComparison(target);
    }

    private void ensureNoMigrationError() throws IOException {
        if (migrationError != null) {
            throw new IOException(migrationError);
        }
    }

    private static Path normalizeForComparison(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, NO_FOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        try {
            Path resolvedExisting = existing.toRealPath();
            return resolvedExisting.resolve(existing.relativize(normalized)).normalize();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static boolean pathsOverlap(Path left, Path right) {
        Path normalizedLeft = normalizeForComparison(left);
        Path normalizedRight = normalizeForComparison(right);
        return normalizedLeft.startsWith(normalizedRight) || normalizedRight.startsWith(normalizedLeft);
    }

    private static boolean isStrictDescendant(Path child, Path parent) {
        Path normalizedChild = normalizeForComparison(child);
        Path normalizedParent = normalizeForComparison(parent);
        return !normalizedChild.equals(normalizedParent) && normalizedChild.startsWith(normalizedParent);
    }

    private static FileMode fileMode(Path file, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink()) {
            return FileMode.SYMLINK;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                return FileMode.EXECUTABLE_FILE;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 等不支持 POSIX 权限的平台使用普通文件模式。
        }
        return FileMode.REGULAR_FILE;
    }

    private static int compareDirCacheEntries(DirCacheEntry left, DirCacheEntry right) {
        byte[] leftPath = left.getRawPath();
        byte[] rightPath = right.getRawPath();
        return Paths.compare(
                leftPath, 0, leftPath.length, left.getRawMode(),
                rightPath, 0, rightPath.length, right.getRawMode());
    }

    private String relativePath(Path path) {
        return projectRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, NO_FOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                clearReadOnlyAttribute(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                clearReadOnlyAttribute(dir);
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
    }

    private static void clearReadOnlyAttribute(Path path) throws IOException {
        DosFileAttributeView view = Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null && view.readAttributes().isReadOnly()) {
            view.setReadOnly(false);
        }
    }

    private static Path normalizeProjectRoot(Path path) {
        Path root = path == null ? Path.of(System.getProperty("user.dir")) : path;
        return root.toAbsolutePath().normalize();
    }

    private static String parentKey(Path path) {
        Path parent = path.getParent();
        return parent == null ? path.toString() : parent.toString();
    }

    private static String safeTurnId(String turnId) {
        return turnId == null || turnId.isBlank() ? "turn-" + Instant.now().toEpochMilli() : turnId.trim();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record WorkspaceFile(Path path, String relativePath, FileMode mode) {
    }

    private record SnapshotTreeEntry(ObjectId objectId, FileMode mode) {
    }
}
