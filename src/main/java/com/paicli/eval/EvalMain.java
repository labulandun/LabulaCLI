package com.paicli.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

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

/**
 * 评测体系 Phase E1 入口（独立 main，不进入 CLI 运行时路径）：
 *
 *   java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.EvalMain --suite custom
 *
 * 每条 case：provision 工作区 → 启动 PaiCLI serve 子进程 → Runtime API 提交任务 →
 * 轮询到终态 → 收集工件 → 逐 grader 打分 → 汇总 report。
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

        Map<String, String> runMeta = new LinkedHashMap<>();
        runMeta.put("run_id", runId);
        runMeta.put("started_at", LocalDateTime.now().toString());
        runMeta.put("git_commit", projectGitCommit(projectRoot));
        runMeta.put("jar", jar.toString());
        runMeta.put("suites", String.join(",", suites));
        writeJson(runDir.resolve("run-meta.json"), toObjectNode(runMeta));

        System.out.println("🧪 PaiCLI Eval run " + runId + "：共 " + cases.size() + " 条用例");
        List<CaseResult> results = new ArrayList<>();
        for (CaseSpec spec : cases) {
            if (!"host".equals(spec.sandbox())) {
                System.out.println("⏭ " + spec.id() + "：sandbox=" + spec.sandbox() + " E1 未支持，跳过");
                results.add(CaseResult.skipped(spec, "sandbox " + spec.sandbox() + " 未支持（Phase E3 交付）"));
                continue;
            }
            for (int runIndex = 1; runIndex <= spec.runs(); runIndex++) {
                CaseResult result = runCase(spec, runIndex, timeoutOverride, jar, runDir, dotEnv, keepWorkspace);
                results.add(result);
                System.out.println((result.taskSuccess() ? "✅ " : "❌ ") + spec.id()
                        + " run-" + runIndex + " (" + result.artifact().status()
                        + ", " + result.artifact().wallTimeMs() / 1000 + "s)");
            }
        }

        Reporter.write(runDir, runMeta, results);
        long executed = results.stream().filter(CaseResult::executed).count();
        long passed = results.stream().filter(CaseResult::taskSuccess).count();
        System.out.println("\n📊 pass_rate: " + passed + "/" + executed
                + "，报告: " + runDir.resolve("report.md"));
    }

    private static CaseResult runCase(CaseSpec spec,
                                      int runIndex,
                                      Integer timeoutOverride,
                                      Path jar,
                                      Path runDir,
                                      Map<String, String> dotEnv,
                                      boolean keepWorkspace) {
        Path caseRunDir = runDir.resolve("cases").resolve(spec.id()).resolve("run-" + runIndex);
        Workspace workspace = null;
        PaiCliProcess process = null;
        long startNanos = System.nanoTime();
        try {
            workspace = Workspace.provision(spec, caseRunDir.resolve("workspace"));
            int port = PaiCliProcess.freePort();
            String apiKey = "eval-" + UUID.randomUUID();
            Path spansFile = caseRunDir.resolve("spans.jsonl");
            process = PaiCliProcess.start(jar, workspace.root(), caseRunDir.resolve("runtime"),
                    caseRunDir.resolve("paicli.log"), spansFile, port, apiKey, dotEnv);

            RuntimeApiClient client = new RuntimeApiClient(port, apiKey);
            String threadId = client.createThread();
            client.submitTurn(threadId, spec.prompt());
            int timeoutSeconds = timeoutOverride != null ? timeoutOverride : spec.timeoutSeconds();
            RuntimeApiClient.TurnOutcome outcome =
                    client.awaitTerminal(threadId, Duration.ofSeconds(timeoutSeconds));
            process.stop();
            long wallTimeMs = (System.nanoTime() - startNanos) / 1_000_000;

            Workspace.DiffStats diff = workspace.diff();
            List<SpanLog.SpanRecord> spans = SpanLog.read(spansFile);
            String traceId = outcome.traceId() != null ? outcome.traceId() : SpanArtifacts.rootTraceId(spans);
            RunArtifact artifact = new RunArtifact(spec.id(), runIndex, outcome.status(),
                    outcome.finalAnswer(), outcome.error(), traceId, outcome.rootObservationId(),
                    wallTimeMs, diff.filesChanged(), diff.insertions(), diff.deletions(),
                    SpanArtifacts.llmStats(spans), SpanArtifacts.toolStats(spans));

            Files.writeString(caseRunDir.resolve("transcript.txt"),
                    "prompt:\n" + spec.prompt() + "\n\nstatus: " + outcome.status()
                            + (outcome.error() == null ? "" : "\nerror: " + outcome.error())
                            + "\n\nfinal answer:\n" + outcome.finalAnswer() + "\n",
                    StandardCharsets.UTF_8);
            Files.writeString(caseRunDir.resolve("workspace.diff"), diff.patch(), StandardCharsets.UTF_8);

            List<Score> scores = gradeAll(spec, artifact, workspace.root());
            scores.addAll(SpanArtifacts.metricScores(artifact));
            CaseResult result = CaseResult.of(spec, runIndex, artifact, scores);
            writeArtifactJson(caseRunDir.resolve("artifact.json"), result);
            return result;
        } catch (Exception e) {
            long wallTimeMs = (System.nanoTime() - startNanos) / 1_000_000;
            RunArtifact artifact = new RunArtifact(spec.id(), runIndex, "error", "",
                    describe(e), null, null, wallTimeMs, 0, 0, 0,
                    RunArtifact.LlmStats.EMPTY, List.of());
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
            if (workspace != null) {
                if (keepWorkspace) {
                    workspace.close();
                } else {
                    workspace.deleteQuietly();
                }
            }
        }
    }

    /** 单个 grader 抛异常不拖垮整条 case：记 0 分并把原因写进 comment。 */
    private static List<Score> gradeAll(CaseSpec spec, RunArtifact artifact, Path workspaceRoot) {
        List<Score> scores = new ArrayList<>();
        for (GraderSpec graderSpec : spec.graders()) {
            try {
                scores.addAll(GraderFactory.create(graderSpec).grade(spec, artifact, workspaceRoot));
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
                  --keep-workspace     保留工作区现场用于排查
                  --help               显示本帮助
                """);
    }
}
