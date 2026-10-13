package com.paicli.eval;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 每次运行独立的一次性工作区：复制 fixture 后用 JGit 打 baseline commit，
 * 结束时相对 baseline 产出 patch 与增删统计。复用 JGit 与 Side-Git 快照同源，不依赖系统 git。
 */
public final class Workspace implements AutoCloseable {

    public record DiffStats(int filesChanged, int insertions, int deletions, String patch) {
    }

    private final Path root;
    private final Git git;
    private DiffStats cachedDiff;

    private Workspace(Path root, Git git) {
        this.root = root;
        this.git = git;
    }

    public static Workspace provision(CaseSpec spec, Path workspaceDir) throws Exception {
        Files.createDirectories(workspaceDir);
        if (spec.gitSource() != null) {
            return provisionFromGit(spec.gitSource(), workspaceDir);
        }
        if (spec.fixtureDir() != null) {
            copyRecursively(spec.fixtureDir(), workspaceDir);
        }
        Git git = Git.init().setDirectory(workspaceDir.toFile()).setInitialBranch("main").call();
        git.add().addFilepattern(".").call();
        commitBaseline(git);
        return new Workspace(workspaceDir, git);
    }

    /**
     * swe-lite：clone 指定 repo 并 checkout 到起点 commit，然后<b>丢弃原仓库历史</b>，
     * 用当前树重建一个单 commit 的 baseline。
     *
     * <p>丢历史是刻意的：Agent 不该能 {@code git log} 看到修复该 bug 的那次提交，
     * 否则评的是"会不会翻历史"而不是"会不会修 bug"。diff 口径也因此和 fixture 用例一致。</p>
     */
    private static Workspace provisionFromGit(CaseSpec.GitSource source, Path workspaceDir) throws Exception {
        Path cloneDir = workspaceDir.resolveSibling(workspaceDir.getFileName() + "-clone");
        deleteRecursively(cloneDir);
        try (Git cloned = Git.cloneRepository()
                .setURI(toUri(source.repo()))
                .setDirectory(cloneDir.toFile())
                .setNoCheckout(source.commit() != null)
                .call()) {
            if (source.commit() != null) {
                cloned.checkout().setName(source.commit()).call();
            }
        }
        copyRecursively(cloneDir, workspaceDir, path -> !path.getFileName().toString().equals(".git"));
        deleteRecursively(cloneDir);

        Git git = Git.init().setDirectory(workspaceDir.toFile()).setInitialBranch("main").call();
        git.add().addFilepattern(".").call();
        commitBaseline(git);
        return new Workspace(workspaceDir, git);
    }

    private static void commitBaseline(Git git) throws Exception {
        git.commit()
                .setMessage("eval baseline")
                .setAuthor("paicli-eval", "eval@paicli.local")
                .setCommitter("paicli-eval", "eval@paicli.local")
                .setSign(false)
                .setAllowEmpty(true)
                .call();
    }

    /** JGit 需要 URI 形式；本地路径直接给绝对路径即可。 */
    private static String toUri(String repo) {
        Path local = Path.of(repo);
        return Files.isDirectory(local) ? local.toAbsolutePath().normalize().toString() : repo;
    }

    public Path root() {
        return root;
    }

    /** 相对 baseline 的变更（含新增/删除文件）。首次调用会把工作区全部改动 stage 后与 HEAD 对比。 */
    public DiffStats diff() throws Exception {
        if (cachedDiff != null) {
            return cachedDiff;
        }
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        Repository repo = git.getRepository();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<DiffEntry> entries;
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repo);
            entries = formatter.scan(headTree(repo), new DirCacheIterator(repo.readDirCache()));
            formatter.format(entries);
        }
        String patch = out.toString(StandardCharsets.UTF_8);
        int insertions = 0;
        int deletions = 0;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                insertions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        cachedDiff = new DiffStats(entries.size(), insertions, deletions, patch);
        return cachedDiff;
    }

    private static CanonicalTreeParser headTree(Repository repo) throws IOException {
        ObjectId headTreeId = repo.resolve("HEAD^{tree}");
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repo.newObjectReader()) {
            parser.reset(reader, headTreeId);
        }
        return parser;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        copyRecursively(source, target, path -> true);
    }

    private static void copyRecursively(Path source, Path target, Predicate<Path> accept) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!accept.test(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (accept.test(file)) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 文件锁：留给人工清理
                }
            });
        } catch (IOException ignored) {
            // 目录已不存在
        }
    }

    @Override
    public void close() {
        git.close();
    }

    /** 删除整个工作区；Windows 下个别句柄未释放时保留现场并提示，不让评测失败。 */
    public void deleteQuietly() {
        close();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 文件锁等场景：留给人工清理
                }
            });
        } catch (IOException ignored) {
            // 工作区已不存在等场景
        }
    }
}
