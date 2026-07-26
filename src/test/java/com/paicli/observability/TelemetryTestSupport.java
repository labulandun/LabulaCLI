package com.paicli.observability;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class TelemetryTestSupport implements AutoCloseable {
    private final RecordingSpanExporter exporter = new RecordingSpanExporter();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();
    private final PaiCliTracing tracing = new PaiCliTracing(openTelemetry);

    PaiCliTracing tracing() {
        return tracing;
    }

    List<SpanData> spans() {
        return List.copyOf(exporter.spans);
    }

    SpanData onlySpanNamed(String name) {
        List<SpanData> matches = exporter.spans.stream()
                .filter(span -> name.equals(span.getName()))
                .toList();
        if (matches.size() != 1) {
            throw new AssertionError("Expected one span named " + name + ", got " + matches.size());
        }
        return matches.get(0);
    }

    @Override
    public void close() {
        tracerProvider.close();
    }

    private static final class RecordingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
