package com.paicli.observability;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryBootstrapTest {

    @Test
    void staysDisabledWhenNoOpenTelemetryConfigurationExists() {
        try (OpenTelemetryBootstrap telemetry = OpenTelemetryBootstrap.initialize(key -> null)) {
            Span span = telemetry.tracer().spanBuilder("disabled-test").startSpan();
            try {
                assertFalse(telemetry.enabled());
                assertTrue(telemetry.tracing() != null);
                assertFalse(span.getSpanContext().isValid());
            } finally {
                span.end();
            }
        }
    }

    @Test
    void explicitDisableWinsOverConfiguredEndpoint() {
        Map<String, String> config = Map.of(
                "PAICLI_OTEL_ENABLED", "false",
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://localhost:4318/v1/traces"
        );

        try (OpenTelemetryBootstrap telemetry = OpenTelemetryBootstrap.initialize(config::get)) {
            assertFalse(telemetry.enabled());
        }
    }

    @Test
    void explicitEnableStartsARealSdk() {
        Map<String, String> config = Map.of(
                "PAICLI_OTEL_ENABLED", "true",
                "OTEL_TRACES_EXPORTER", "none",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none"
        );

        try (OpenTelemetryBootstrap telemetry = OpenTelemetryBootstrap.initialize(config::get)) {
            Span span = telemetry.tracer().spanBuilder("enabled-test").startSpan();
            try {
                assertTrue(telemetry.enabled());
                assertTrue(span.getSpanContext().isValid());
            } finally {
                span.end();
            }
        }
    }

    @Test
    void configuredTraceEndpointEnablesTelemetryWithoutExtraFlag() {
        Map<String, String> config = Map.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://localhost:4318/v1/traces");

        try (OpenTelemetryBootstrap telemetry = OpenTelemetryBootstrap.initialize(config::get)) {
            assertTrue(telemetry.enabled());
        }
    }

    @Test
    void closeCanBeCalledMoreThanOnce() {
        Map<String, String> config = Map.of(
                "PAICLI_OTEL_ENABLED", "true",
                "OTEL_TRACES_EXPORTER", "none",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none"
        );
        OpenTelemetryBootstrap telemetry = OpenTelemetryBootstrap.initialize(config::get);

        assertDoesNotThrow(telemetry::close);
        assertDoesNotThrow(telemetry::close);
    }
}
