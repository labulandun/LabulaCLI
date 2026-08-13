package com.paicli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmRequestWireFormat;
import com.paicli.llm.StepClient;
import com.paicli.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextEstimatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void accountsMessagesImagesReasoningToolCallsFramingSchemaAndEnvelope() throws Exception {
        LlmClient client = new FixedClient(true, Map.of("reasoning_effort", "high"));
        RequestContextEstimator estimator = new RequestContextEstimator(
                client, ContextProfile.custom(128_000, 40_000, 8_192, 2_048));
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("system text"),
                LlmClient.Message.user(List.of(
                        LlmClient.ContentPart.text("user text"),
                        LlmClient.ContentPart.imageUrl("https://example.test/image.png"))),
                LlmClient.Message.assistant(
                        "hidden reasoning",
                        "assistant text",
                        List.of(new LlmClient.ToolCall(
                                "call-1",
                                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"A.java\"}")))),
                LlmClient.Message.tool("call-1", "tool result")
        );
        List<LlmClient.Tool> tools = List.of(tool("read_file", "read a file"));

        RequestContextBudget budget = estimator.estimate(messages, tools);

        assertEquals(MemoryEntry.estimateTokens("system text"), budget.systemContentTokens());
        assertTrue(budget.userContentTokens() > MemoryEntry.estimateTokens("user text"));
        assertEquals(1_024, budget.imageTokens());
        assertTrue(budget.assistantContentTokens() > 0);
        assertTrue(budget.toolMessageContentTokens() > 0);
        assertEquals(MemoryEntry.estimateTokens("hidden reasoning"), budget.replayedReasoningTokens());
        assertTrue(budget.toolCallTokens() > 0);
        assertTrue(budget.messageFramingTokens() > messages.size() * 4L);
        assertTrue(budget.toolsSchemaWireTokens() > 0);
        assertTrue(budget.requestEnvelopeTokens() > 0);
        assertEquals(4, budget.messageCount());
        assertEquals(1, budget.toolDefinitionCount());
        assertEquals(budget.modelViewMessageTokens()
                        + budget.messageFramingTokens()
                        + budget.toolsSchemaWireTokens()
                        + budget.requestEnvelopeTokens(),
                budget.estimatedInputTokens());
    }

    @Test
    void reasoningIsCountedOnlyWhenProviderReplaysIt() {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.assistant("r".repeat(4_000), "answer"));
        ContextProfile profile = ContextProfile.custom(128_000, 40_000, 8_192, 2_048);

        RequestContextBudget withoutReplay = new RequestContextEstimator(
                new FixedClient(false, Map.of()), profile).estimate(messages, null);
        RequestContextBudget withReplay = new RequestContextEstimator(
                new FixedClient(true, Map.of()), profile).estimate(messages, null);

        assertEquals(0, withoutReplay.replayedReasoningTokens());
        assertEquals(MemoryEntry.estimateTokens("r".repeat(4_000)),
                withReplay.replayedReasoningTokens());
        assertTrue(withReplay.estimatedInputTokens() > withoutReplay.estimatedInputTokens());
    }

    @Test
    void replayedReasoningCanCrossTheHardCeiling() {
        ContextProfile profile = ContextProfile.custom(8_000, 4_000, 2_000, 800);
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.assistant("r".repeat(30_000), "short answer"));

        RequestContextBudget ignored = new RequestContextEstimator(
                new FixedClient(false, Map.of()), profile).estimate(messages, null);
        RequestContextEstimator replaying = new RequestContextEstimator(
                new FixedClient(true, Map.of()), profile);

        assertTrue(ignored.safeToSend());
        assertThrows(ContextViewBuildException.class,
                () -> replaying.validate(messages, null));
    }

    @Test
    void emptyOrInvalidContentPartsUseTheSameContentFallbackAsTransport() {
        RequestContextEstimator estimator = RequestContextEstimator.forClient(
                new FixedClient(false, Map.of()));
        String largeContent = "fallback".repeat(2_000);
        LlmClient.Message emptyParts = new LlmClient.Message(
                "user", largeContent, null, null, null, List.of());
        LlmClient.Message invalidParts = new LlmClient.Message(
                "user", largeContent, null, null, null,
                List.of(new LlmClient.ContentPart("image_url", null, null, "", null)));

        assertEquals(MemoryEntry.estimateTokens(largeContent),
                estimator.estimate(List.of(emptyParts), null).userContentTokens());
        assertEquals(MemoryEntry.estimateTokens(largeContent),
                estimator.estimate(List.of(invalidParts), null).userContentTokens());
    }

    @Test
    void nullAndEmptyToolsCostZeroWhileDynamicWireSchemaIsMonotonic() throws Exception {
        RequestContextEstimator estimator = RequestContextEstimator.forClient(
                new FixedClient(false, Map.of()));
        List<LlmClient.Message> messages = List.of(LlmClient.Message.user("hello"));
        LlmClient.Tool small = tool("small", "short");
        LlmClient.Tool large = tool("large", "schema ".repeat(8_000));

        assertEquals(0, estimator.estimate(messages, null).toolsSchemaWireTokens());
        assertEquals(0, estimator.estimate(messages, List.of()).toolsSchemaWireTokens());
        assertTrue(estimator.estimate(messages, List.of(large)).toolsSchemaWireTokens()
                > estimator.estimate(messages, List.of(small)).toolsSchemaWireTokens());

        var wire = LlmRequestWireFormat.toolsWireObject(MAPPER, List.of(small));
        assertEquals("function", wire.path("tools").path(0).path("type").asText());
        assertEquals("small", wire.path("tools").path(0).path("function").path("name").asText());
        assertTrue(wire.path("tools").path(0).path("function").has("parameters"));
    }

    @Test
    void stepProviderEnvelopeUsesTheSameVisibleFieldsAsWireBuilder() {
        StepClient step = new StepClient("test", "step-3.7-flash-2603", "https://example.test/v1");
        var envelope = LlmRequestWireFormat.requestEnvelope(MAPPER, step);
        RequestContextBudget budget = RequestContextEstimator.forClient(step)
                .estimate(List.of(LlmClient.Message.user("hello")), null);

        assertEquals("deepseek-style", envelope.path("reasoning_format").asText());
        assertEquals("high", envelope.path("reasoning_effort").asText());
        assertEquals(MemoryEntry.estimateTokens(envelope.toString()), budget.requestEnvelopeTokens());
    }

    @Test
    void validatesSoftAndHardBoundariesWithoutTreatingReserveAsInput() {
        ContextProfile profile = ContextProfile.custom(8_000, 4_000, 2_000, 800);
        RequestContextEstimator estimator = new RequestContextEstimator(
                new FixedClient(false, Map.of()), profile);
        RequestContextBudget small = estimator.estimate(
                List.of(LlmClient.Message.user("x".repeat(4_000))), null);
        RequestContextBudget large = estimator.estimate(
                List.of(LlmClient.Message.user("x".repeat(30_000))), null);

        assertEquals(5_200, small.hardInputCeilingTokens());
        assertEquals(4_680, small.softCompressionTriggerTokens());
        assertEquals(small.estimatedInputTokens() + 2_800, small.budgetFootprintTokens());
        assertFalse(small.needsProjection());
        assertTrue(small.safeToSend());
        assertTrue(large.needsProjection());
        assertFalse(large.safeToSend());
        ContextViewBuildException error = assertThrows(
                ContextViewBuildException.class,
                () -> estimator.validate(List.of(
                        LlmClient.Message.user("x".repeat(30_000))), null));
        assertTrue(error.getMessage().startsWith(ContextViewBuildException.CODE));
    }

    @Test
    void softAndHardComparisonsAreInclusiveAtTheDocumentedBoundaries() {
        RequestContextBudget atBoundary = budgetWithInput(90);
        RequestContextBudget overBoundary = budgetWithInput(91);

        assertTrue(atBoundary.needsProjection(), "estimatedInput >= soft trigger must project");
        assertTrue(atBoundary.safeToSend(), "estimatedInput == hard ceiling is sendable");
        assertFalse(overBoundary.safeToSend());
    }

    private static RequestContextBudget budgetWithInput(long input) {
        return new RequestContextBudget(
                100,
                input,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                5,
                5,
                90,
                90,
                1,
                0);
    }

    private static LlmClient.Tool tool(String name, String description) throws Exception {
        return new LlmClient.Tool(
                name,
                description,
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));
    }

    private static final class FixedClient implements LlmClient {
        private final boolean replayReasoning;
        private final Map<String, Object> envelope;

        private FixedClient(boolean replayReasoning, Map<String, Object> envelope) {
            this.replayReasoning = replayReasoning;
            this.envelope = envelope;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModelName() {
            return "fixed-model";
        }

        @Override
        public String getProviderName() {
            return "fixed";
        }

        @Override
        public boolean replaysReasoningContent() {
            return replayReasoning;
        }

        @Override
        public Map<String, Object> requestEnvelopeFields() {
            return envelope;
        }
    }
}
