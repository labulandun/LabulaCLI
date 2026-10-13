package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJudgeGraderTest {

    @TempDir
    Path tempDir;

    @Test
    void liveJudgeUsesRubricAnswerAndDiffThenHitsCache() throws Exception {
        Path rubricRoot = tempDir.resolve("rubrics");
        Files.createDirectories(rubricRoot);
        Files.writeString(rubricRoot.resolve("code-quality-v1.md"),
                "Score correctness and maintainability.", StandardCharsets.UTF_8);
        Path cacheRoot = tempDir.resolve("cache");
        RecordingJudgeClient client = new RecordingJudgeClient(
                "provider-a", "model-a", "{\"score\":4,\"reason\":\"Correct with a minor omission\"}");
        LlmJudgeGrader grader = new LlmJudgeGrader(
                EvalFixtures.graderSpec("llm_judge", "rubric", "code-quality-v1"),
                dependencies(LlmJudgeGrader.Mode.LIVE, client, rubricRoot, cacheRoot));

        CaseSpec spec = EvalFixtures.caseSpec("judge-1");
        RunArtifact artifact = EvalFixtures.artifact("judge-1", "completed",
                "Implemented the requested fix.");
        GraderContext context = new GraderContext(tempDir.resolve("run"),
                "diff --git a/App.java b/App.java\n+return value;");

        List<Score> first = grader.grade(spec, artifact, tempDir, context);
        List<Score> second = grader.grade(spec, artifact, tempDir, context);

        assertEquals("judge_score", first.get(0).name());
        assertEquals(4.0, first.get(0).value());
        assertEquals(Score.DataType.NUMERIC, first.get(0).dataType());
        assertTrue(first.get(0).comment().contains("minor omission"));
        assertTrue(first.get(0).comment().contains("cache=miss"));
        assertTrue(second.get(0).comment().contains("cache=hit"));
        assertEquals(1, client.calls.get());
        assertTrue(client.userPrompt.contains("<rubric name=\"code-quality-v1\">"));
        assertTrue(client.userPrompt.contains("Implemented the requested fix."));
        assertTrue(client.userPrompt.contains("+return value;"));
        assertTrue(client.systemPrompt.contains("untrusted data"));

        Path cacheFile;
        try (var files = Files.list(cacheRoot)) {
            cacheFile = files.findFirst().orElseThrow();
        }
        String persisted = Files.readString(cacheFile, StandardCharsets.UTF_8);
        assertFalse(persisted.contains("Implemented the requested fix."));
        assertFalse(persisted.contains("+return value;"));
    }

    @Test
    void malformedResponseProducesNonGatingJudgeError() throws Exception {
        Path rubricRoot = writeRubric("answer-quality-v1");
        RecordingJudgeClient client = new RecordingJudgeClient(
                "provider-a", "model-a", "not-json");
        LlmJudgeGrader grader = new LlmJudgeGrader(
                EvalFixtures.graderSpec("llm_judge", "rubric", "answer-quality-v1"),
                dependencies(LlmJudgeGrader.Mode.LIVE, client, rubricRoot, tempDir.resolve("cache")));

        List<Score> scores = grader.grade(EvalFixtures.caseSpec("judge-2"),
                EvalFixtures.artifact("judge-2"), tempDir, GraderContext.empty());

        assertEquals("judge_error", scores.get(0).name());
        assertEquals(Score.DataType.NUMERIC, scores.get(0).dataType());
        CaseResult result = CaseResult.of(EvalFixtures.caseSpec("judge-2"), 1,
                EvalFixtures.artifact("judge-2"),
                List.of(Score.bool("tests_pass", true, "ok"), scores.get(0)));
        assertTrue(result.taskSuccess());
    }

    @Test
    void cacheOnlyReusesLiveDecisionAndOffDoesNotLoadRubric() throws Exception {
        Path rubricRoot = writeRubric("code-quality-v1");
        Path cacheRoot = tempDir.resolve("cache");
        RecordingJudgeClient liveClient = new RecordingJudgeClient(
                "provider-a", "model-a", "{\"score\":5,\"reason\":\"Excellent\"}");
        GraderSpec spec = EvalFixtures.graderSpec("llm_judge", "rubric", "code-quality-v1");
        CaseSpec caseSpec = EvalFixtures.caseSpec("judge-3");
        RunArtifact artifact = EvalFixtures.artifact("judge-3");

        new LlmJudgeGrader(spec,
                dependencies(LlmJudgeGrader.Mode.LIVE, liveClient, rubricRoot, cacheRoot))
                .grade(caseSpec, artifact, tempDir, GraderContext.empty());

        RecordingJudgeClient unused = new RecordingJudgeClient(
                "provider-a", "model-a", "{\"score\":1,\"reason\":\"must not run\"}");
        List<Score> cached = new LlmJudgeGrader(spec,
                dependencies(LlmJudgeGrader.Mode.CACHE_ONLY, null, rubricRoot, cacheRoot))
                .grade(caseSpec, artifact, tempDir, GraderContext.empty());
        List<Score> off = new LlmJudgeGrader(
                EvalFixtures.graderSpec("llm_judge", "rubric", "../missing"),
                dependencies(LlmJudgeGrader.Mode.OFF, unused,
                        tempDir.resolve("missing-rubrics"), cacheRoot))
                .grade(caseSpec, artifact, tempDir, GraderContext.empty());

        assertEquals("judge_score", cached.get(0).name());
        assertTrue(cached.get(0).comment().contains("cache=hit"));
        assertEquals("judge_skipped", off.get(0).name());
        assertEquals(0, unused.calls.get());
    }

    @Test
    void parserRejectsOutOfRangeAndMissingReason() {
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":6,\"reason\":\"too high\"}"));
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":4,\"reason\":\"\"}"));
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":4.5,\"reason\":\"decimal\"}"));
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":4,\"reason\":123}"));
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":4,\"reason\":\"ok\"} trailing"));
        assertThrows(Exception.class,
                () -> LlmJudgeGrader.parseDecision("{\"score\":4294967297,\"reason\":\"overflow\"}"));
    }

    @Test
    void rubricRepositoryRejectsTraversal() {
        RubricRepository repository = new RubricRepository(tempDir.resolve("rubrics"));
        assertThrows(IllegalArgumentException.class, () -> repository.load("../secret"));
    }

    private Path writeRubric(String name) throws IOException {
        Path root = tempDir.resolve("rubrics");
        Files.createDirectories(root);
        Files.writeString(root.resolve(name + ".md"), "A versioned rubric.", StandardCharsets.UTF_8);
        return root;
    }

    private LlmJudgeGrader.Dependencies dependencies(LlmJudgeGrader.Mode mode,
                                                      JudgeClient client,
                                                      Path rubricRoot,
                                                      Path cacheRoot) {
        return new LlmJudgeGrader.Dependencies(
                mode, client, "provider-a", "model-a",
                new RubricRepository(rubricRoot), new JudgeCache(cacheRoot));
    }

    private static final class RecordingJudgeClient implements JudgeClient {
        private final String provider;
        private final String model;
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();
        private String systemPrompt;
        private String userPrompt;

        private RecordingJudgeClient(String provider, String model, String response) {
            this.provider = provider;
            this.model = model;
            this.response = response;
        }

        @Override
        public String judge(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            return response;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public String model() {
            return model;
        }
    }
}
