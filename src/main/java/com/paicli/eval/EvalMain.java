package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 评测体系 Phase E1 入口（独立 main，不进入 CLI 运行时路径）：
 *
 *   java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom
 *
 * 每条 case：provision 工作区 → 可选 setup_cmd → 启动 PaiCLI serve 子进程 →
 * Runtime API 提交任务 → 轮询到终态 → 收集工件 → 逐 grader 打分 → 汇总 report。
 * 每个 (case, run) 单元自带独立工作区与子进程，默认并发 4。
 * 总设计见 docs/evaluation-system-design.md。
 */
public final class EvalMain {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        if (options.containsKey("help")) {
            printUsage();
            return;
        }
        Path projectRoot = Path.of("").toAbsolutePath();
        Path evalHome = projectRoot.resolve(options.getOrDefault("eval-home", "eval"));
        Path jar = projectRoot.resolve(options.getOrDefault("jar", "target/paicli-1.0-SNAPSHOT.jar"));
        if (!Files.isRegularFile(jar)) {
            System.err.println("❌ 被测 jar 不存在: " + jar + "，请先执行 mvn clean package");
            System.exit(2);
        }
        List<String> suites = List.of(options.getOrDefault("suite", "custom").split(","));
        List<CaseSpec> cases = SuiteLoader.load(evalHome, suites, options.get("case"));
        if (cases.isEmpty()) {
            System.err.println("❌ 没有匹配的评测用例 (suites=" + suites + ", case=" + options.get("case") + ")");
            System.exit(2);
        }

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path runDir = projectRoot.resolve(options.getOrDefault("out", "eval/runs")).resolve(runId);
        Files.createDirectories(runDir);
        boolean keepWorkspace = options.containsKey("keep-workspace");
        Integer timeoutOverride = options.containsKey("timeout")
                ? Integer.parseInt(options.get("timeout")) : null;
        Map<String, String> dotEnv = DotEnv.read(projectRoot.resolve(".env"));
        SecretRedactor redactor = new SecretRedactor(dotEnv);
        int concurrency = Math.max(1, Integer.parseInt(options.getOrDefault("concurrency", "4")));
        PortAllocator ports = new PortAllocator();

        Map<String, String> runMeta = new LinkedHashMap<>();
        runMeta.put("run_id", runId);
        runMeta.put("started_at", LocalDateTime.now().toString());
        runMeta.put("git_commit", projectGitCommit(projectRoot));
        runMeta.put("jar", jar.toString());
        runMeta.put("suites", String.join(",", suites));
        runMeta.put("concurrency", String.valueOf(concurrency));
        writeJson(runDir.resolve("run-meta.json"), toObjectNode(runMeta));

        System.out.println("🧪 PaiCLI Eval run " + runId + "：共 " + cases.size() + " 条用例");
        // docker 只探测一次；不可用时 docker 用例整体 skip，绝不降级到宿主机执行
        DockerExecutor.Availability docker = cases.stream().anyMatch(c -> "docker".equals(c.sandbox()))
                ? DockerExecutor.probe()
                : new DockerExecutor.Availability(false, "无 docker 用例，未探测");
        if (cases.stream().anyMatch(c -> "docker".equals(c.sandbox()))) {
            System.out.println((docker.available() ? "🐳 " : "⚠️ ") + "Docker: " + docker.detail());
        }

        List<CaseResult> results = new ArrayList<>();
        List<Runnable> units = new ArrayList<>();
        // 结果按提交顺序回填，保证报告顺序与 case 顺序一致，不受完成先后影响
        List<CaseResult> slots = new ArrayList<>();
        for (CaseSpec spec : cases) {
            String skipReason = sandboxSkipReason(spec, docker);
            if (skipReason != null) {
                System.out.println("⏭ " + spec.id() + "：" + skipReason);
                results.add(CaseResult.skipped(spec, skipReason));
                continue;
            }
            for (int runIndex = 1; runIndex <= spec.runs(); runIndex++) {
                int slot = slots.size();
                int currentRun = runIndex;
                slots.add(null);
                units.add(() -> {
                    CaseResult result = runCase(spec, currentRun, timeoutOverride, jar, runDir, dotEnv,
                            redactor, ports, keepWorkspace);
                    slots.set(slot, result);
                    System.out.println((result.taskSuccess() ? "✅ " : "❌ ") + spec.id()
                            + " run-" + currentRun + " (" + result.artifact().status()
                            + ", " + result.artifact().wallTimeMs() / 1000 + "s)");
                });
            }
        }
        runUnits(units, concurrency);
        slots.stream().filter(java.util.Objects::nonNull).forEach(results::add);

        Reporter.write(runDir, runMeta, results);
        long executed = results.stream().filter(CaseResult::executed).count();
        long passed = results.stream().filter(CaseResult::taskSuccess).count();
        System.out.println("\n📊 pass_rate: " + passed + "/" + executed
                + "，pass@k: " + Reporter.passAtKSummary(results)
                + "，报告: " + runDir.resolve("report.md"));
    }

    /** 每个 unit 自带独立工作区与子进程，天然可并行；并发数由 --concurrency 限制。 */
    private static void runUnits(List<Runnable> units, int concurrency) throws InterruptedException {
        if (units.isEmpty()) {
            return;
        }
        if (concurrency <= 1) {
            units.forEach(Runnable::run);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, units.size()), r -> {
            Thread thread = new Thread(r, "paicli-eval-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = units.stream().map(pool::submit).toList();
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // runCase 内部已把异常转成 error 结果，走到这里说明是回填逻辑本身出问题
                    System.err.println("⚠️ 评测单元异常: " + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static CaseResult runCase(CaseSpec spec,
                                      int runIndex,
                                      Integer timeoutOverride,
                                      Path jar,
                                      Path runDir,
                                      Map<String, String> dotEnv,
                                      SecretRedactor redactor,
                                      PortAllocator ports,
                                      boolean keepWorkspace) {
        Path caseRunDir = runDir.resolve("cases").resolve(spec.id()).resolve("run-" + runIndex);
        Workspace workspace = null;
        PaiCliProcess process = null;
        CommandRunner commandRunner = CommandRunner.forCase(spec);
        int port = -1;
        long startNanos = System.nanoTime();
        try {
            workspace = Workspace.provision(spec, caseRunDir.resolve("workspace"));
            plantCanary(caseRunDir.resolve("canary"));
            runSetup(spec, workspace.root(), commandRunner);
            port = ports.allocate();
            String apiKey = "eval-" + UUID.randomUUID();
            Path outDir = caseRunDir.resolve("out");
            Path spansFile = outDir.resolve("spans.jsonl");
            Path auditDir = outDir.resolve("audit");
            LaunchSpec launchSpec = new LaunchSpec(jar, workspace.root(), outDir, spansFile,
                    outDir.resolve("runtime"), auditDir, port, apiKey, dotEnv);
            SandboxExecutor sandbox = "docker".equals(spec.sandbox())
                    ? new DockerExecutor(spec.dockerImage())
                    : new HostExecutor();
            process = PaiCliProcess.start(sandbox, launchSpec, caseRunDir.resolve("paicli.log"));

            RuntimeApiClient client = new RuntimeApiClient(port, apiKey);
            String threadId = client.createThread();
            client.submitTurn(threadId, spec.prompt());
            int timeoutSeconds = timeoutOverride != null ? timeoutOverride : spec.timeoutSeconds();
            RuntimeApiClient.TurnOutcome raw =
                    client.awaitTerminal(threadId, Duration.ofSeconds(timeoutSeconds));
            // 密钥必须在进入任何工件之前抹掉：safety suite 会诱导 Agent 打印环境变量
            RuntimeApiClient.TurnOutcome outcome = new RuntimeApiClient.TurnOutcome(
                    raw.status(), redactor.redact(raw.finalAnswer()), redactor.redact(raw.error()),
                    raw.traceId(), raw.rootObservationId());
            process.stop();
            long wallTimeMs = (System.nanoTime() - startNanos) / 1_000_000;

            Workspace.DiffStats diff = workspace.diff();
            List<SpanLog.SpanRecord> spans = SpanLog.read(spansFile);
            String traceId = outcome.traceId() != null ? outcome.traceId() : SpanArtifacts.rootTraceId(spans);
            RunArtifact artifact = new RunArtifact(spec.id(), runIndex, outcome.status(),
                    outcome.finalAnswer(), outcome.error(), traceId, outcome.rootObservationId(),
                    wallTimeMs, diff.filesChanged(), diff.insertions(), diff.deletions(),
                    SpanArtifacts.llmStats(spans), SpanArtifacts.toolStats(spans),
                    redactAudit(AuditTrail.read(auditDir), redactor));

            Files.writeString(caseRunDir.resolve("transcript.txt"),
                    "prompt:\n" + spec.prompt() + "\n\nstatus: " + outcome.status()
                            + (outcome.error() == null ? "" : "\nerror: " + outcome.error())
                            + "\n\nfinal answer:\n" + outcome.finalAnswer() + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(caseRunDir.resolve("workspace.diff"),
                    redactor.redact(diff.patch()), StandardCharsets.UTF_8);

            List<Score> scores = gradeAll(spec, artifact, workspace.root(), commandRunner);
            scores.addAll(SpanArtifacts.metricScores(artifact));
            CaseResult result = CaseResult.of(spec, runIndex, artifact, scores);
            writeArtifactJson(caseRunDir.resolve("artifact.json"), result);
            return result;
        } catch (Exception e) {
            long wallTimeMs = (System.nanoTime() - startNanos) / 1_000_000;
            RunArtifact artifact = RunArtifact.failure(spec.id(), runIndex, "error", describe(e), wallTimeMs);
            CaseResult result = new CaseResult(spec.id(), spec.suite(), runIndex, artifact,
                    List.of(), false, null);
            try {
                writeArtifactJson(caseRunDir.resolve("artifact.json"), result);
            } catch (Exception ignored) {
                // 工件目录都写不进去时保留原始异常即可
            }
            return result;
        } finally {
            if (process != null) {
                process.stop();
            }
            if (port > 0) {
                ports.release(port);
            }
            if (workspace != null) {
                if (keepWorkspace) {
                    workspace.close();
                } else {
                    workspace.deleteQuietly();
                }
            }
        }
    }

    /**
     * provision 之后、Agent 启动之前跑一次，用来保证起点可编译（swe-lite / terminal 常需要）。
     * 失败直接抛：起点就坏了的话，后面测出来的是环境问题不是模型能力。
     */
    private static void runSetup(CaseSpec spec, Path workspace, CommandRunner runner) throws Exception {
        if (spec.setupCmd() == null || spec.setupCmd().isBlank()) {
            return;
        }
        CommandRunner.Result result = runner.run(workspace, spec.setupCmd(), 600);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("setup_cmd 失败 (exit=" + result.exitCode()
                    + ", @" + runner.describe() + "): " + spec.setupCmd() + "\n" + result.output());
        }
    }

    private static List<AuditTrail.AuditRecord> redactAudit(List<AuditTrail.AuditRecord> records,
                                                            SecretRedactor redactor) {
        return records.stream()
                .map(r -> new AuditTrail.AuditRecord(r.tool(), redactor.redact(r.args()),
                        r.outcome(), redactor.redact(r.reason()), r.approver()))
                .toList();
    }

    /**
     * 在工作区之外放一个哨兵文件。PathGuard 正常工作时 Agent 碰不到它，
     * SafetyGrader 用它证明是否发生过路径逃逸。
     */
    private static void plantCanary(Path canaryDir) throws IOException {
        Files.createDirectories(canaryDir);
        Files.writeString(canaryDir.resolve(SafetyGrader.CANARY_FILE),
                SafetyGrader.CANARY_CONTENT, StandardCharsets.UTF_8);
    }

    /** 返回 null 表示可以执行；否则是 skip 原因。 */
    private static String sandboxSkipReason(CaseSpec spec, DockerExecutor.Availability docker) {
        return switch (spec.sandbox()) {
            case "host" -> null;
            case "docker" -> docker.available() ? null : "需要 Docker 沙盒但不可用：" + docker.detail();
            default -> "未知 sandbox: " + spec.sandbox();
        };
    }

    /** 单个 grader 抛异常不拖垮整条 case：记 0 分并把原因写进 comment。 */
    private static List<Score> gradeAll(CaseSpec spec, RunArtifact artifact, Path workspaceRoot,
                                        CommandRunner runner) {
        List<Score> scores = new ArrayList<>();
        for (GraderSpec graderSpec : spec.graders()) {
            try {
                scores.addAll(GraderFactory.create(graderSpec, runner).grade(spec, artifact, workspaceRoot));
            } catch (Exception e) {
                scores.add(Score.bool(graderSpec.type() + "_error", false, "grader 异常: " + describe(e)));
            }
        }
        return scores;
    }

    private static void writeArtifactJson(Path file, CaseResult result) throws Exception {
        Files.createDirectories(file.getParent());
        ObjectNode node = MAPPER.valueToTree(result.artifact());
        ArrayNode scores = node.putArray("scores");
        for (Score score : result.scores()) {
            ObjectNode scoreNode = scores.addObject();
            scoreNode.put("name", score.name());
            scoreNode.put("value", score.value());
            scoreNode.put("data_type", score.dataType().name());
            scoreNode.put("comment", score.comment());
        }
        node.put("task_success", result.taskSuccess());
        writeJson(file, node);
    }

    private static String projectGitCommit(Path projectRoot) {
        try (Git git = Git.open(projectRoot.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? "unknown" : head.name();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeJson(Path file, ObjectNode node) throws Exception {
        Files.writeString(file, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private static ObjectNode toObjectNode(Map<String, String> values) {
        ObjectNode node = MAPPER.createObjectNode();
        values.forEach(node::put);
        return node;
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("未知参数: " + arg);
            }
            String key = arg.substring(2);
            boolean flag = "help".equals(key) || "keep-workspace".equals(key);
            if (flag) {
                options.put(key, "true");
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("参数缺少值: " + arg);
                }
                options.put(key, args[++i]);
            }
        }
        return options;
    }

    private static void printUsage() {
        System.out.println("""
                用法: java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain [选项]
                  --suite <s1,s2>      要运行的 suite（默认 custom）
                  --case <case-id>     只运行指定 case
                  --jar <path>         被测 jar（默认 target/paicli-1.0-SNAPSHOT.jar）
                  --eval-home <dir>    eval 根目录（默认 eval）
                  --out <dir>          输出目录（默认 eval/runs）
                  --timeout <seconds>  覆盖所有 case 的超时
                  --concurrency <n>    并行执行的 case 数（默认 4，设 1 退回串行）
                  --keep-workspace     保留工作区现场用于排查（safety 用例排查时建议开）
                  --help               显示本帮助
                """);
    }
}
