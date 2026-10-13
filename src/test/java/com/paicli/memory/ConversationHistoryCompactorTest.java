package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextProfile;
import com.paicli.context.RequestContextEstimator;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolResultArtifactIndex;
import com.paicli.tool.ToolResultArtifactRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void belowTriggerReturnsImmutableSourceSnapshot() {
        StubCompactor compactor = new StubCompactor("first");
        List<LlmClient.Message> source = new ArrayList<>(List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user("hi")));

        HistoryProjectionResult result = compactor.project(
                source, HistoryProjectionState.empty(), 100_000);

        assertFalse(result.compacted());
        assertEquals(source, result.modelMessages());
        assertEquals(0, compactor.initialCalls);
        assertThrows(UnsupportedOperationException.class,
                () -> result.modelMessages().add(LlmClient.Message.user("mutate")));
        assertEquals(2, source.size());
    }

    @Test
    void completeRequestAccountingLetsDynamicToolSchemaTriggerProjection() throws Exception {
        MutableClient client = new MutableClient("test", "test", false);
        StubCompactor compactor = new StubCompactor(
                client, "schema-trigger", 3, ConversationHistoryCompactor.DEFAULT_SUMMARY_POLICY_VERSION);
        List<LlmClient.Message> source = new ArrayList<>(List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user("round-1"),
                LlmClient.Message.assistant("answer-1"),
                LlmClient.Message.user("round-2"),
                LlmClient.Message.assistant("answer-2"),
                LlmClient.Message.user("round-3"),
                LlmClient.Message.assistant("answer-3"),
                LlmClient.Message.user("round-4")
        ));
        LlmClient.Tool largeTool = new LlmClient.Tool(
                "dynamic_mcp_tool",
                "schema ".repeat(12_000),
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}"));
        RequestContextEstimator estimator = new RequestContextEstimator(
                client, ContextProfile.custom(32_000, 14_000));

        HistoryProjectionResult result = compactor.project(
                source,
                HistoryProjectionState.empty(),
                estimator,
                List.of(largeTool),
                ToolResultArtifactIndex.empty());

        assertTrue(result.compacted());
        assertTrue(result.checkpointAdvanced());
        assertEquals(1, compactor.initialCalls);
        assertEquals(8, source.size(), "projection must not rewrite source history");
    }

    @Test
    void summaryRequestUsesTheProvidedRequestProfileBeforeCallingModelHook() {
        MutableClient client = new MutableClient("test", "test", false);
        StubCompactor compactor = new StubCompactor(
                client, "summary-budget", 1, ConversationHistoryCompactor.DEFAULT_SUMMARY_POLICY_VERSION);
        List<LlmClient.Message> source = List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user("old-large " + "x".repeat(30_000)),
                LlmClient.Message.assistant("old answer"),
                LlmClient.Message.user("recent tail"));
        RequestContextEstimator estimator = new RequestContextEstimator(
                client, ContextProfile.custom(8_000, 4_000));

        HistoryProjectionResult result = compactor.project(
                source,
                HistoryProjectionState.empty(),
                estimator,
                null,
                ToolResultArtifactIndex.empty());

        assertTrue(result.summaryFailed());
        assertEquals(0, compactor.initialCalls,
                "the provided request profile must block the summary before its model hook");
        assertFalse(result.nextState().hasCheckpoint());
        assertEquals(source, result.modelMessages());
    }

    @Test
    void previewUsesValidCheckpointWithoutCallingSummaryOrAdvancingState() {
        StubCompactor compactor = new StubCompactor("preview", 3);
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);
        int callsBeforePreview = compactor.initialCalls + compactor.incrementalCalls;

        List<LlmClient.Message> preview = compactor.previewModelMessages(
                source, first.nextState(), 100, ToolResultArtifactIndex.empty());

        assertEquals(first.modelMessages(), preview);
        assertEquals(callsBeforePreview, compactor.initialCalls + compactor.incrementalCalls);
        assertThrows(UnsupportedOperationException.class,
                () -> preview.add(LlmClient.Message.user("mutate")));
    }

    @Test
    void projectionNeverMutatesSourceAndKeepsRecentRounds() {
        StubCompactor compactor = new StubCompactor("first", 2);
        List<LlmClient.Message> source = history(6, 5_000);
        List<LlmClient.Message> before = List.copyOf(source);

        HistoryProjectionResult result = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        assertEquals(before, source, "source history must remain field-for-field unchanged");
        assertTrue(result.compacted());
        assertTrue(result.checkpointAdvanced());
        assertEquals(7, result.modelMessages().size());
        assertEquals("system", result.modelMessages().get(0).role());
        assertTrue(result.modelMessages().get(1).content().contains("[已压缩的历史对话摘要]"));
        assertTrue(result.modelMessages().get(1).content().contains("first"));
        assertEquals("assistant", result.modelMessages().get(2).role());
        assertTrue(result.modelMessages().get(3).content().startsWith("Q4"));
        assertTrue(result.modelMessages().get(5).content().startsWith("Q5"));
        assertEquals(1, compactor.initialCalls);
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
    }

    @Test
    void splitAtUserBoundaryPreservesToolCallAndResultPair() {
        StubCompactor compactor = new StubCompactor("pair", 2);
        List<LlmClient.Message> source = new ArrayList<>();
        source.add(LlmClient.Message.system("S"));
        source.add(LlmClient.Message.user("OldQ1 " + longText(4_000)));
        source.add(LlmClient.Message.assistant("OldA1"));
        source.add(LlmClient.Message.user("OldQ2 " + longText(4_000)));
        source.add(LlmClient.Message.assistant("OldA2"));
        source.add(LlmClient.Message.user("RecentQ1"));
        List<LlmClient.ToolCall> calls = List.of(new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        source.add(LlmClient.Message.assistant("hidden", null, calls));
        source.add(LlmClient.Message.tool("call-1", "file content"));
        source.add(LlmClient.Message.assistant("done"));
        source.add(LlmClient.Message.user("RecentQ2"));
        source.add(LlmClient.Message.assistant("answer"));
        List<LlmClient.Message> before = List.copyOf(source);

        HistoryProjectionResult result = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        assertEquals(before, source);
        int recent = indexOfContent(result.modelMessages(), "RecentQ1");
        assertTrue(recent >= 3);
        assertEquals("assistant", result.modelMessages().get(recent + 1).role());
        assertEquals("call-1", result.modelMessages().get(recent + 1).toolCalls().get(0).id());
        assertEquals("tool", result.modelMessages().get(recent + 2).role());
        assertEquals("call-1", result.modelMessages().get(recent + 2).toolCallId());
    }

    @Test
    void growingHistorySummarizesOnlyNewlyCoveredPrefix() {
        StubCompactor compactor = new StubCompactor("v1", 3);
        List<LlmClient.Message> source = history(7, 3_000);

        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);
        int firstCovered = first.coveredUntilExclusive();

        source.add(LlmClient.Message.user("Q7 " + longText(3_000)));
        source.add(LlmClient.Message.assistant("A7 " + longText(3_000)));
        HistoryProjectionResult second = compactor.project(
                source, first.nextState(), 100);

        assertTrue(second.checkpointAdvanced());
        assertTrue(second.coveredUntilExclusive() > firstCovered);
        assertEquals(1, compactor.initialCalls);
        assertEquals(1, compactor.incrementalCalls);
        assertEquals(List.copyOf(source.subList(firstCovered, second.coveredUntilExclusive())),
                compactor.lastIncrementalMessages);
        assertFalse(compactor.lastIncrementalMessages.stream()
                .anyMatch(message -> message.content() != null && message.content().startsWith("Q0")));
    }

    @Test
    void coveredPrefixMutationInvalidatesCheckpointAndRebuildsFromSource() {
        StubCompactor compactor = new StubCompactor("v1", 3);
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        source.set(1, LlmClient.Message.user("changed " + longText(3_000)));
        HistoryProjectionResult rebuilt = compactor.project(source, first.nextState(), 100);

        assertTrue(rebuilt.checkpointAdvanced());
        assertEquals(2, compactor.initialCalls);
        assertEquals(0, compactor.incrementalCalls);
        assertNotEquals(first.coveredPrefixDigest(), rebuilt.coveredPrefixDigest());
    }

    @Test
    void nonAppendTailMutationInvalidatesCheckpointAndRebuildsFromSource() {
        StubCompactor compactor = new StubCompactor("v1", 3);
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        int covered = first.coveredUntilExclusive();
        source.add(covered + 1, LlmClient.Message.assistant("inserted into retained tail"));
        HistoryProjectionResult rebuilt = compactor.project(source, first.nextState(), 100);

        assertTrue(rebuilt.checkpointAdvanced());
        assertEquals(2, compactor.initialCalls);
        assertEquals(0, compactor.incrementalCalls);
    }

    @Test
    void retainPolicyChangeInvalidatesCheckpointAndRebuildsAtNewUserBoundary() {
        List<LlmClient.Message> source = history(7, 3_000);
        StubCompactor retainThree = new StubCompactor("three", 3);
        HistoryProjectionResult first = retainThree.project(
                source, HistoryProjectionState.empty(), 100);

        StubCompactor retainTwo = new StubCompactor("two", 2);
        HistoryProjectionResult rebuilt = retainTwo.project(source, first.nextState(), 100);

        assertTrue(rebuilt.checkpointAdvanced());
        assertEquals(1, retainTwo.initialCalls);
        assertEquals(0, retainTwo.incrementalCalls);
        assertEquals(2, rebuilt.nextState().checkpoint().retainRecentRounds());
        assertTrue(rebuilt.coveredUntilExclusive() > first.coveredUntilExclusive());
    }

    @Test
    void providerModelPolicyAndReasoningReplayChangesInvalidateCheckpoint() {
        MutableClient client = new MutableClient("provider-a", "model-a", false);
        StubCompactor firstCompactor = new StubCompactor(client, "v1", 3, "policy-a");
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = firstCompactor.project(
                source, HistoryProjectionState.empty(), 100);

        client.provider = "provider-b";
        StubCompactor providerChanged = new StubCompactor(client, "provider", 3, "policy-a");
        HistoryProjectionResult providerResult = providerChanged.project(source, first.nextState(), 100);
        assertEquals(1, providerChanged.initialCalls);
        assertEquals("provider-b", providerResult.nextState().checkpoint().provider());

        client.model = "model-b";
        StubCompactor modelChanged = new StubCompactor(client, "model", 3, "policy-a");
        HistoryProjectionResult modelResult = modelChanged.project(source, providerResult.nextState(), 100);
        assertEquals(1, modelChanged.initialCalls);
        assertEquals("model-b", modelResult.nextState().checkpoint().model());

        client.replay = true;
        StubCompactor replayChanged = new StubCompactor(client, "replay", 3, "policy-a");
        HistoryProjectionResult replayResult = replayChanged.project(source, modelResult.nextState(), 100);
        assertEquals(1, replayChanged.initialCalls);
        assertTrue(replayResult.nextState().checkpoint().reasoningReplayEnabled());

        StubCompactor policyChanged = new StubCompactor(client, "policy", 3, "policy-b");
        HistoryProjectionResult policyResult = policyChanged.project(source, replayResult.nextState(), 100);
        assertEquals(1, policyChanged.initialCalls);
        assertEquals("policy-b", policyResult.nextState().checkpoint().summaryPolicyVersion());
    }

    @Test
    void digestIncludesReasoningToolAndContentPartFields() {
        StubCompactor compactor = new StubCompactor("digest", 2);
        List<LlmClient.Message> source = historyWithStructuredCoveredMessage();
        HistoryProjectionResult first = compactor.project(source, HistoryProjectionState.empty(), 100);

        LlmClient.Message original = source.get(2);
        source.set(2, new LlmClient.Message(
                original.role(), original.content(), "changed reasoning", original.toolCalls(),
                original.toolCallId(), original.contentParts()));
        HistoryProjectionResult reasoningChanged = compactor.project(source, first.nextState(), 100);
        assertEquals(2, compactor.initialCalls);

        LlmClient.ToolCall changedCall = new LlmClient.ToolCall(
                "changed-id", new LlmClient.ToolCall.Function("write_file", "{\"path\":\"b\"}"));
        source.set(2, new LlmClient.Message(
                original.role(), original.content(), original.reasoningContent(), List.of(changedCall),
                original.toolCallId(), original.contentParts()));
        compactor.project(source, reasoningChanged.nextState(), 100);
        assertEquals(3, compactor.initialCalls);

        source.set(2, new LlmClient.Message(
                original.role(), original.content(), original.reasoningContent(), original.toolCalls(),
                original.toolCallId(), List.of(LlmClient.ContentPart.text("changed part"))));
        compactor.project(source, HistoryProjectionState.empty(), 100);
        assertEquals(4, compactor.initialCalls);
    }

    @Test
    void artifactManifestIsDynamicAndDoesNotInvalidateSummaryCheckpoint() {
        StubCompactor compactor = new StubCompactor("artifact", 3);
        List<LlmClient.Message> source = history(8, 3_000);
        String manifest = "tool-result://session/manifest";
        ToolResultArtifactRef firstRef = artifact("a1", "sha-a1");
        ToolResultArtifactIndex firstIndex = new ToolResultArtifactIndex(
                manifest, 1, 1, List.of(firstRef));
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 7_000, firstIndex);

        ToolResultArtifactRef secondRef = artifact("a2", "sha-a2");
        ToolResultArtifactIndex grown = new ToolResultArtifactIndex(
                manifest, 2, 1, List.of(secondRef, firstRef));
        HistoryProjectionResult second = compactor.project(
                source, first.nextState(), 7_000, grown);

        assertFalse(second.checkpointAdvanced());
        assertEquals(1, compactor.initialCalls);
        String modelText = second.modelMessages().stream()
                .map(LlmClient.Message::content)
                .filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(modelText.contains(manifest));
        assertTrue(modelText.contains("artifact_count: 2"));
        assertTrue(modelText.contains("tool-result://session/a2"));
        assertTrue(modelText.contains("read_tool_result"));
    }

    @Test
    void modelMessagesProtectNestedMessageLists() {
        List<LlmClient.ToolCall> mutableCalls = new ArrayList<>();
        mutableCalls.add(new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        List<LlmClient.ContentPart> mutableParts = new ArrayList<>();
        mutableParts.add(LlmClient.ContentPart.text("part"));
        LlmClient.Message structured = new LlmClient.Message(
                "assistant", "content", null, mutableCalls, null, mutableParts);
        List<LlmClient.Message> source = List.of(LlmClient.Message.system("S"), structured);

        HistoryProjectionResult result = new StubCompactor("unused").project(
                source, HistoryProjectionState.empty(), 100_000);
        mutableCalls.clear();
        mutableParts.clear();

        assertEquals(1, result.modelMessages().get(1).toolCalls().size());
        assertEquals(1, result.modelMessages().get(1).contentParts().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.modelMessages().get(1).toolCalls().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.modelMessages().get(1).contentParts().clear());
    }

    @Test
    void explicitReasoningBlocksAreRemovedFromStoredAndIncrementalSummaries() {
        StubCompactor compactor = new StubCompactor(
                "<think type=\"hidden\">SECRET_REASONING</think>\n" + validSummary("clean"), 3);
        List<LlmClient.Message> source = history(7, 3_000);

        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);
        assertFalse(first.nextState().checkpoint().summary().contains("SECRET_REASONING"));

        source.add(LlmClient.Message.user("Q7 " + longText(3_000)));
        source.add(LlmClient.Message.assistant("A7 " + longText(3_000)));
        compactor.project(source, first.nextState(), 100);
        assertFalse(compactor.lastPreviousSummary.contains("SECRET_REASONING"));
    }

    @Test
    void unclosedReasoningContainerFailsClosedWithoutCheckpoint() {
        StubCompactor compactor = new StubCompactor("unused", 3);
        compactor.rawInitialSummary = "<analysis>SECRET_REASONING\n" + validSummary("hidden");
        List<LlmClient.Message> source = history(7, 3_000);

        HistoryProjectionResult result = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        assertTrue(result.summaryFailed());
        assertFalse(result.nextState().hasCheckpoint());
        assertEquals(source, result.modelMessages());
    }

    @Test
    void taintedExternalCheckpointIsInvalidatedAndRebuilt() {
        StubCompactor compactor = new StubCompactor("clean", 3);
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);
        SummaryCheckpoint checkpoint = first.nextState().checkpoint();
        SummaryCheckpoint tainted = new SummaryCheckpoint(
                checkpoint.systemEnd(),
                checkpoint.coveredUntilExclusive(),
                checkpoint.coveredPrefixDigest(),
                checkpoint.sourceSizeAtCheckpoint(),
                checkpoint.sourceAppendBaselineDigest(),
                "<think>SECRET_REASONING</think>\n" + checkpoint.summary(),
                checkpoint.summaryPolicyVersion(),
                checkpoint.retainRecentRounds(),
                checkpoint.provider(),
                checkpoint.model(),
                checkpoint.reasoningReplayEnabled(),
                checkpoint.artifactManifestHandle(),
                checkpoint.artifactManifestSchemaVersion());
        HistoryProjectionState taintedState = HistoryProjectionState.observed(
                tainted,
                first.nextState().observedSystemEnd(),
                first.nextState().observedSourceSize(),
                first.nextState().observedSourceDigest());

        HistoryProjectionResult rebuilt = compactor.project(source, taintedState, 100);

        assertTrue(rebuilt.checkpointAdvanced());
        assertEquals(2, compactor.initialCalls);
        assertFalse(rebuilt.modelMessages().stream()
                .map(LlmClient.Message::content)
                .filter(java.util.Objects::nonNull)
                .anyMatch(content -> content.contains("SECRET_REASONING")));
    }

    @Test
    void summaryFailuresDoNotPolluteSourceOrState() {
        List<LlmClient.Message> source = history(7, 3_000);
        List<LlmClient.Message> before = List.copyOf(source);

        StubCompactor ioFailure = new StubCompactor("unused", 3);
        ioFailure.failInitial = true;
        HistoryProjectionResult ioResult = ioFailure.project(
                source, HistoryProjectionState.empty(), 100);
        assertTrue(ioResult.summaryFailed());
        assertFalse(ioResult.nextState().hasCheckpoint());
        assertEquals(before, source);
        assertEquals(before, ioResult.modelMessages());

        for (String invalid : List.of("", "missing headings", longText(17_000))) {
            StubCompactor invalidCompactor = new StubCompactor("unused", 3);
            invalidCompactor.rawInitialSummary = invalid;
            HistoryProjectionResult invalidResult = invalidCompactor.project(
                    source, HistoryProjectionState.empty(), 100);
            assertTrue(invalidResult.summaryFailed());
            assertFalse(invalidResult.nextState().hasCheckpoint());
            assertEquals(before, source);
        }
    }

    @Test
    void incrementalFailureUsesOldSummaryAndCompleteCurrentTail() {
        StubCompactor compactor = new StubCompactor("stable", 3);
        List<LlmClient.Message> source = history(7, 3_000);
        HistoryProjectionResult first = compactor.project(
                source, HistoryProjectionState.empty(), 100);
        int oldCovered = first.coveredUntilExclusive();
        source.add(LlmClient.Message.user("brand-new-user " + longText(3_000)));
        source.add(LlmClient.Message.assistant("brand-new-assistant " + longText(3_000)));
        List<LlmClient.Message> before = List.copyOf(source);
        compactor.failIncremental = true;

        HistoryProjectionResult failed = compactor.project(source, first.nextState(), 100);

        assertTrue(failed.summaryFailed());
        assertEquals(first.nextState(), failed.nextState());
        assertEquals(before, source);
        assertEquals(source.subList(oldCovered, source.size()),
                failed.modelMessages().subList(3, failed.modelMessages().size()));
        assertTrue(failed.modelMessages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("brand-new-user")));
    }

    @Test
    void invalidIncrementalSummaryKeepsPreviousCheckpointAndCompleteTail() {
        for (String invalid : List.of("", "missing headings", longText(17_000))) {
            StubCompactor compactor = new StubCompactor("stable", 3);
            List<LlmClient.Message> source = history(7, 3_000);
            HistoryProjectionResult first = compactor.project(
                    source, HistoryProjectionState.empty(), 100);
            int oldCovered = first.coveredUntilExclusive();
            source.add(LlmClient.Message.user("new-tail " + longText(3_000)));
            List<LlmClient.Message> before = List.copyOf(source);
            compactor.rawIncrementalSummary = invalid;

            HistoryProjectionResult failed = compactor.project(source, first.nextState(), 100);

            assertTrue(failed.summaryFailed());
            assertEquals(first.nextState(), failed.nextState());
            assertEquals(before, source);
            assertEquals(source.subList(oldCovered, source.size()),
                    failed.modelMessages().subList(3, failed.modelMessages().size()));
        }
    }

    @Test
    void summaryPromptKeepsRecoverySectionsAndOmitsHiddenReasoning() {
        CapturingClient client = new CapturingClient(validSummary("captured"));
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(client, 2);
        List<LlmClient.Message> source = history(5, 3_000);
        source.set(2, LlmClient.Message.assistant("SECRET_REASONING", "answer"));

        HistoryProjectionResult result = compactor.project(
                source, HistoryProjectionState.empty(), 100);

        assertTrue(result.checkpointAdvanced());
        String prompt = client.lastMessages.get(1).content();
        for (String heading : List.of(
                "## Primary Request and Intent", "## Current Work", "## Pending Tasks",
                "## All User Messages", "## Key Technical Concepts", "## Files and Code Sections",
                "## Problem Solving", "## Errors and Fixes", "## Optional Next Step")) {
            assertTrue(prompt.contains(heading));
        }
        assertTrue(prompt.contains("按时间顺序列出所有非 tool result 的用户消息"));
        assertTrue(prompt.contains("不要遗漏用户明确要求、限制、否定条件"));
        assertFalse(prompt.contains("SECRET_REASONING"));
    }

    private static List<LlmClient.Message> history(int rounds, int charsPerMessage) {
        List<LlmClient.Message> source = new ArrayList<>();
        source.add(LlmClient.Message.system("SYSTEM"));
        for (int i = 0; i < rounds; i++) {
            source.add(LlmClient.Message.user("Q" + i + " " + longText(charsPerMessage)));
            source.add(LlmClient.Message.assistant("A" + i + " " + longText(charsPerMessage)));
        }
        return source;
    }

    private static List<LlmClient.Message> historyWithStructuredCoveredMessage() {
        List<LlmClient.Message> source = history(6, 3_000);
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call-id", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}"));
        source.set(2, new LlmClient.Message(
                "assistant", "content", "hidden", List.of(call), null,
                List.of(LlmClient.ContentPart.text("part"))));
        return source;
    }

    private static ToolResultArtifactRef artifact(String id, String digest) {
        return new ToolResultArtifactRef(
                id,
                "tool-result://session/" + id,
                "sha256:project",
                "session",
                "read_file",
                "call-" + id,
                Instant.parse("2026-08-12T00:00:00Z"),
                20_000,
                20_000,
                digest,
                true,
                false);
    }

    private static int indexOfContent(List<LlmClient.Message> messages, String content) {
        for (int i = 0; i < messages.size(); i++) {
            if (content.equals(messages.get(i).content())) {
                return i;
            }
        }
        return -1;
    }

    private static String validSummary(String marker) {
        return """
                ## Primary Request and Intent
                %s
                ## Current Work
                %s
                ## Pending Tasks
                无
                ## All User Messages
                已覆盖
                ## Key Technical Concepts
                projection
                ## Files and Code Sections
                无
                ## Problem Solving
                无
                ## Errors and Fixes
                无
                ## Optional Next Step
                继续
                """.formatted(marker, marker).trim();
    }

    private static String longText(int chars) {
        return "x".repeat(Math.max(0, chars));
    }

    private static class StubCompactor extends ConversationHistoryCompactor {
        int initialCalls;
        int incrementalCalls;
        boolean failInitial;
        boolean failIncremental;
        String rawInitialSummary;
        String rawIncrementalSummary;
        List<LlmClient.Message> lastIncrementalMessages = List.of();
        String lastPreviousSummary = "";
        private final String marker;

        StubCompactor(String marker) {
            this(marker, 3);
        }

        StubCompactor(String marker, int retainRecent) {
            this(new MutableClient("test", "test", false), marker, retainRecent,
                    DEFAULT_SUMMARY_POLICY_VERSION);
        }

        StubCompactor(LlmClient client, String marker, int retainRecent, String policy) {
            super(client, retainRecent, policy);
            this.marker = marker;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            initialCalls++;
            if (failInitial) {
                throw new IOException("summary unavailable");
            }
            return rawInitialSummary != null ? rawInitialSummary : validSummary(marker);
        }

        @Override
        protected String summarizeIncrementally(String previousSummary,
                                                List<LlmClient.Message> messages) throws IOException {
            incrementalCalls++;
            lastPreviousSummary = previousSummary == null ? "" : previousSummary;
            lastIncrementalMessages = List.copyOf(messages);
            if (failIncremental) {
                throw new IOException("incremental summary unavailable");
            }
            return rawIncrementalSummary != null
                    ? rawIncrementalSummary
                    : validSummary(marker + "-incremental");
        }
    }

    private static final class MutableClient implements LlmClient {
        private String provider;
        private String model;
        private boolean replay;

        private MutableClient(String provider, String model, boolean replay) {
            this.provider = provider;
            this.model = model;
            this.replay = replay;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", validSummary("client"), null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return model;
        }

        @Override
        public String getProviderName() {
            return provider;
        }

        @Override
        public boolean replaysReasoningContent() {
            return replay;
        }
    }

    private static final class CapturingClient implements LlmClient {
        private final String response;
        private List<Message> lastMessages = List.of();

        private CapturingClient(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            lastMessages = List.copyOf(messages);
            return new ChatResponse("assistant", response, null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "model";
        }

        @Override
        public String getProviderName() {
            return "provider";
        }
    }
}
