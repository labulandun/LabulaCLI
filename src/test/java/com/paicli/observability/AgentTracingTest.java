package com.paicli.observability;

import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTracingTest {

    @Test
    void reactRunCreatesOneRootWithGenerationAndToolChildren(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "trace me");
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            Agent agent = new Agent(new ToolThenAnswerClient(), registry, telemetry.tracing());

            String answer = agent.run("inspect sample.txt");

            assertTrue(answer.contains("observed trace me"));
            List<SpanData> spans = telemetry.spans();
            SpanData root = spans.stream()
                    .filter(span -> PaiCliTracing.AGENT_SPAN_NAME.equals(span.getName()))
                    .findFirst()
                    .orElseThrow();
            List<SpanData> generations = spans.stream()
                    .filter(span -> "generation".equals(stringAttribute(span, PaiCliTracing.ATTR_OBSERVATION_TYPE)))
                    .toList();
            List<SpanData> tools = spans.stream()
                    .filter(span -> "tool".equals(stringAttribute(span, PaiCliTracing.ATTR_OBSERVATION_TYPE)))
                    .toList();

            assertEquals(2, generations.size());
            assertEquals(1, tools.size());
            generations.forEach(span -> assertChildOf(span, root));
            tools.forEach(span -> assertChildOf(span, root));
            assertTrue(stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_INPUT).contains("inspect sample.txt"));
            assertTrue(stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_OUTPUT).contains("observed trace me"));
            assertTrue(!stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_OUTPUT).contains("secret internal thought"));

            assertEquals(StatusCode.UNSET, root.getStatus().getStatusCode());
            assertEquals("completed", stringAttribute(root, PaiCliTracing.ATTR_EXIT_REASON));
            assertEquals("DEFAULT", stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_LEVEL));

            PaiCliTracing.TraceReference reference = telemetry.tracing().takeLastCompletedRun()
                    .orElseThrow();
            assertEquals(root.getTraceId(), reference.traceId());
            assertEquals(root.getSpanId(), reference.observationId());
        }
    }

    /**
     * 回归：ReAct 把 IOException 吞成一句错误文案返回，如果不显式落到根 span 上，
     * Langfuse 里所有 run 都会是绿的，失败率这个评测信号直接消失。
     */
    @Test
    void llmFailureMarksRootRunAsError(@TempDir Path tempDir) {
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            Agent agent = new Agent(new FailingClient(), registry, telemetry.tracing());

            String answer = agent.run("anything");

            assertTrue(answer.contains("upstream 503"));
            SpanData root = telemetry.onlySpanNamed(PaiCliTracing.AGENT_SPAN_NAME);
            assertEquals(StatusCode.ERROR, root.getStatus().getStatusCode());
            assertEquals("llm_error", stringAttribute(root, PaiCliTracing.ATTR_EXIT_REASON));
            assertEquals("ERROR", stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_LEVEL));
            assertEquals("upstream 503", stringAttribute(root, PaiCliTracing.ATTR_STATUS_MESSAGE));
            assertTrue(root.getEvents().stream().anyMatch(event -> "exception".equals(event.getName())),
                    "LLM 异常必须作为 exception event 落到根 span 上");
            assertTrue(stringAttribute(root, PaiCliTracing.ATTR_OBSERVATION_OUTPUT).contains("upstream 503"));
        }
    }

    private static void assertChildOf(SpanData child, SpanData root) {
        assertEquals(root.getTraceId(), child.getTraceId());
        assertEquals(root.getSpanId(), child.getParentSpanId());
    }

    private static String stringAttribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static final class ToolThenAnswerClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (calls.getAndIncrement() == 0) {
                ToolCall call = new ToolCall("call-read",
                        new ToolCall.Function("read_file", "{\"path\":\"sample.txt\"}"));
                return new ChatResponse("assistant", "", null, List.of(call), 10, 2, 0);
            }
            return new ChatResponse("assistant", "observed trace me", "secret internal thought", List.of(), 12, 4, 3);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }
    }

    private static final class FailingClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            throw new IOException("upstream 503");
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }
    }
}
