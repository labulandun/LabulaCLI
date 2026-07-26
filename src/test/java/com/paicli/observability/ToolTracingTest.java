package com.paicli.observability;

import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTracingTest {

    @Test
    void parallelToolsRemainChildrenOfAgentRun(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "hello telemetry");
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.setTracing(telemetry.tracing());

            String rootSpanId;
            String traceId;
            try (PaiCliTracing.AgentRun run = telemetry.tracing()
                    .startAgentRun("inspect files", "test", "test-model")) {
                rootSpanId = run.observationId();
                traceId = run.traceId();
                List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                        new ToolRegistry.ToolInvocation("call-read", "read_file",
                                "{\"path\":\"sample.txt\"}"),
                        new ToolRegistry.ToolInvocation("call-list", "list_dir",
                                "{\"path\":\".\"}")
                ));
                assertEquals(2, results.size());
                assertTrue(results.get(0).result().contains("hello telemetry"));
                run.output("done");
            }

            List<SpanData> tools = telemetry.spans().stream()
                    .filter(span -> "tool".equals(stringAttribute(span, "langfuse.observation.type")))
                    .toList();
            assertEquals(2, tools.size());
            for (SpanData tool : tools) {
                assertEquals(rootSpanId, tool.getParentSpanId());
                assertEquals(traceId, tool.getTraceId());
            }

            SpanData read = tools.stream()
                    .filter(span -> "read_file".equals(span.getName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("call-read",
                    stringAttribute(read, "langfuse.observation.metadata.tool_call_id"));
            assertEquals("{\"path\":\"sample.txt\"}",
                    stringAttribute(read, "langfuse.observation.input"));
            assertTrue(stringAttribute(read, "langfuse.observation.output").contains("hello telemetry"));
        }
    }

    @Test
    void capsLargeToolOutputBeforeExport() {
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            try (PaiCliTracing.AgentRun agentRun = telemetry.tracing()
                    .startAgentRun("large output", "test", "test-model")) {
                try (PaiCliTracing.ToolRun toolRun = telemetry.tracing()
                        .startTool("read_file", "call-large", "{}")) {
                    toolRun.output("x".repeat(20_000));
                }
            }

            SpanData tool = telemetry.spans().stream()
                    .filter(span -> "read_file".equals(span.getName()))
                    .findFirst()
                    .orElseThrow();
            String output = stringAttribute(tool, "langfuse.observation.output");
            assertTrue(output.length() <= 16_384);
            assertTrue(output.endsWith("[truncated]"));
        }
    }

    /**
     * 回归：catch 一旦写在 try-with-resources 外层，span 会先 close，
     * 异常就再也写不进 Trace，工具失败会伪装成正常结束。
     */
    @Test
    void toolExceptionMarksSpanAsError(@TempDir Path tempDir) {
        try (TelemetryTestSupport telemetry = new TelemetryTestSupport()) {
            ToolRegistry registry = new ThrowingToolRegistry();
            registry.setProjectPath(tempDir.toString());
            registry.setTracing(telemetry.tracing());

            try (PaiCliTracing.AgentRun run = telemetry.tracing()
                    .startAgentRun("trigger failure", "test", "test-model")) {
                List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                        new ToolRegistry.ToolInvocation("call-boom", "read_file", "{}")));
                assertEquals(1, results.size());
                assertTrue(results.get(0).result().contains("tool blew up"));
                run.output("done");
            }

            SpanData tool = telemetry.onlySpanNamed("read_file");
            assertEquals(StatusCode.ERROR, tool.getStatus().getStatusCode());
            assertEquals("ERROR", stringAttribute(tool, PaiCliTracing.ATTR_OBSERVATION_LEVEL));
            assertEquals("tool blew up", stringAttribute(tool, PaiCliTracing.ATTR_STATUS_MESSAGE));
            assertTrue(stringAttribute(tool, PaiCliTracing.ATTR_OBSERVATION_OUTPUT).contains("tool blew up"));
            assertTrue(tool.getEvents().stream().anyMatch(event -> "exception".equals(event.getName())),
                    "工具异常必须作为 exception event 落到 span 上");
        }
    }

    private static String stringAttribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static final class ThrowingToolRegistry extends ToolRegistry {
        @Override
        protected ToolOutput doExecuteTool(String name, String argumentsJson) {
            throw new IllegalStateException("tool blew up");
        }
    }
}
