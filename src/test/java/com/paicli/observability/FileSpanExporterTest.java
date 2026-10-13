package com.paicli.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSpanExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void spansAreAppendedAsJsonLinesSynchronously() throws Exception {
        Path file = tempDir.resolve("nested").resolve("spans.jsonl");
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(FileSpanExporter.create(file)))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        Tracer tracer = sdk.getTracer("test");

        Span root = tracer.spanBuilder("paicli.react.run").startSpan();
        root.setAttribute("langfuse.observation.type", "agent");
        try (Scope ignored = root.makeCurrent()) {
            Span tool = tracer.spanBuilder("grep_code").startSpan();
            tool.setAttribute("langfuse.observation.type", "tool");
            tool.setStatus(StatusCode.ERROR, "boom");
            tool.end();
        }
        root.end();

        // SimpleSpanProcessor 同步导出：不调用 provider.close()/flush 也必须已落盘
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        JsonNode toolLine = MAPPER.readTree(lines.get(0));
        assertEquals("grep_code", toolLine.path("name").asText());
        assertEquals("ERROR", toolLine.path("status").asText());
        assertEquals("tool", toolLine.path("attributes").path("langfuse.observation.type").asText());
        assertTrue(toolLine.hasNonNull("parent_span_id"));
        assertTrue(toolLine.path("duration_ms").asLong() >= 0);

        JsonNode rootLine = MAPPER.readTree(lines.get(1));
        assertEquals("paicli.react.run", rootLine.path("name").asText());
        assertEquals(toolLine.path("trace_id").asText(), rootLine.path("trace_id").asText());
        assertEquals(rootLine.path("span_id").asText(), toolLine.path("parent_span_id").asText());

        provider.close();
    }

    @Test
    void bootstrapFileModeCreatesWorkingTracer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spans.jsonl");
        Map<String, String> config = Map.of(
                "PAICLI_OTEL_ENABLED", "true",
                "PAICLI_OTEL_TRACES_EXPORTER", "file",
                "PAICLI_OTEL_TRACES_FILE", file.toString());
        try (OpenTelemetryBootstrap bootstrap = OpenTelemetryBootstrap.initialize(config::get)) {
            assertTrue(bootstrap.enabled());
            Span span = bootstrap.tracer().spanBuilder("probe").startSpan();
            span.end();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("probe", MAPPER.readTree(lines.get(0)).path("name").asText());
    }

    @Test
    void bootstrapFileModeWithoutPathStaysDisabled() {
        Map<String, String> config = Map.of(
                "PAICLI_OTEL_ENABLED", "true",
                "PAICLI_OTEL_TRACES_EXPORTER", "file");
        try (OpenTelemetryBootstrap bootstrap = OpenTelemetryBootstrap.initialize(config::get)) {
            assertTrue(!bootstrap.enabled());
        }
    }
}
