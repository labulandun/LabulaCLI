package com.paicli.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TracingLlmClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recordsGenerationAsChildWithExclusiveTokenBucketsAndNoReasoning() throws Exception {
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport();
             PaiCliTracing.AgentRun run = telemetry.tracing()
                     .startAgentRun("fix the bug", "deepseek", "deepseek-v4")) {
            LlmClient observed = telemetry.tracing().traceLlmClient(new FixedLlmClient());

            LlmClient.ChatResponse response = observed.chat(
                    List.of(LlmClient.Message.user("hello")),
                    List.of()
            );
            run.output("final answer");

            assertEquals("answer", response.content());
        }

        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            // Separate support is intentionally not used for assertions.
        }
    }

    @Test
    void exportedGenerationContainsExpectedLangfuseAttributes() throws Exception {
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            String rootSpanId;
            try (PaiCliTracing.AgentRun run = telemetry.tracing()
                    .startAgentRun("fix the bug", "deepseek", "deepseek-v4")) {
                rootSpanId = run.observationId();
                telemetry.tracing().traceLlmClient(new FixedLlmClient()).chat(
                        List.of(LlmClient.Message.user("hello Authorization: Bearer sk-lf-super-secret")),
                        List.of()
                );
                run.output("final answer");
            }

            SpanData generation = telemetry.onlySpanNamed("paicli.llm.chat");
            assertEquals(rootSpanId, generation.getParentSpanId());
            assertEquals("generation", stringAttribute(generation, "langfuse.observation.type"));
            assertEquals("deepseek-v4", stringAttribute(generation, "langfuse.observation.model.name"));
            assertEquals("deepseek", stringAttribute(generation, "langfuse.observation.metadata.provider"));

            JsonNode input = MAPPER.readTree(stringAttribute(generation, "langfuse.observation.input"));
            assertEquals("user", input.get(0).path("role").asText());
            assertTrue(input.get(0).path("content").asText().startsWith("hello"));
            assertFalse(input.toString().contains("sk-lf-super-secret"));
            assertTrue(input.toString().contains("[REDACTED]"));

            JsonNode output = MAPPER.readTree(stringAttribute(generation, "langfuse.observation.output"));
            assertEquals("answer", output.path("content").asText());
            assertFalse(output.toString().contains("private reasoning"));

            JsonNode usage = MAPPER.readTree(stringAttribute(generation, "langfuse.observation.usage_details"));
            assertEquals(80, usage.path("input").asInt());
            assertEquals(20, usage.path("input_cached_tokens").asInt());
            assertEquals(5, usage.path("output").asInt());
            assertEquals(105, usage.path("total").asInt());
        }
    }

    private static String stringAttribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static final class FixedLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return response();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return response();
        }

        @Override
        public String getModelName() {
            return "deepseek-v4";
        }

        @Override
        public String getProviderName() {
            return "deepseek";
        }

        private ChatResponse response() {
            return new ChatResponse(
                    "assistant",
                    "answer",
                    "private reasoning",
                    null,
                    100,
                    5,
                    20
            );
        }
    }
}
