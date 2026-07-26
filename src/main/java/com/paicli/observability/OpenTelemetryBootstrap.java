package com.paicli.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Optional OpenTelemetry SDK lifecycle for PaiCLI.
 *
 * <p>The SDK remains disabled unless {@code PAICLI_OTEL_ENABLED=true} or an
 * OTLP endpoint is configured. This keeps the default CLI startup free of
 * background exporter traffic while allowing standard OTEL configuration to
 * be supplied through environment variables, system properties, or PaiCLI's
 * {@code .env} lookup.</p>
 */
public final class OpenTelemetryBootstrap implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryBootstrap.class);
    private static final String INSTRUMENTATION_SCOPE = "com.paicli";
    private static final long FLUSH_TIMEOUT_SECONDS = 10;

    private static final Map<String, String> OTEL_PROPERTY_NAMES = Map.ofEntries(
            Map.entry("OTEL_SDK_DISABLED", "otel.sdk.disabled"),
            Map.entry("OTEL_SERVICE_NAME", "otel.service.name"),
            Map.entry("OTEL_RESOURCE_ATTRIBUTES", "otel.resource.attributes"),
            Map.entry("OTEL_TRACES_EXPORTER", "otel.traces.exporter"),
            Map.entry("OTEL_METRICS_EXPORTER", "otel.metrics.exporter"),
            Map.entry("OTEL_LOGS_EXPORTER", "otel.logs.exporter"),
            Map.entry("OTEL_EXPORTER_OTLP_PROTOCOL", "otel.exporter.otlp.protocol"),
            Map.entry("OTEL_EXPORTER_OTLP_ENDPOINT", "otel.exporter.otlp.endpoint"),
            Map.entry("OTEL_EXPORTER_OTLP_HEADERS", "otel.exporter.otlp.headers"),
            Map.entry("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "otel.exporter.otlp.traces.protocol"),
            Map.entry("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "otel.exporter.otlp.traces.endpoint"),
            Map.entry("OTEL_EXPORTER_OTLP_TRACES_HEADERS", "otel.exporter.otlp.traces.headers"),
            Map.entry("OTEL_EXPORTER_OTLP_COMPRESSION", "otel.exporter.otlp.compression"),
            Map.entry("OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "otel.exporter.otlp.traces.compression"),
            Map.entry("OTEL_EXPORTER_OTLP_TIMEOUT", "otel.exporter.otlp.timeout"),
            Map.entry("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "otel.exporter.otlp.traces.timeout"),
            Map.entry("OTEL_TRACES_SAMPLER", "otel.traces.sampler"),
            Map.entry("OTEL_TRACES_SAMPLER_ARG", "otel.traces.sampler.arg")
    );

    private final boolean enabled;
    private final OpenTelemetry openTelemetry;
    private final OpenTelemetrySdk sdk;
    private final PaiCliTracing tracing;
    private final AtomicBoolean closed = new AtomicBoolean();

    private OpenTelemetryBootstrap(boolean enabled, OpenTelemetry openTelemetry, OpenTelemetrySdk sdk) {
        this.enabled = enabled;
        this.openTelemetry = openTelemetry;
        this.sdk = sdk;
        this.tracing = new PaiCliTracing(openTelemetry);
    }

    public static OpenTelemetryBootstrap initialize() {
        return initialize(key -> {
            String property = System.getProperty(key);
            return property == null || property.isBlank() ? System.getenv(key) : property;
        });
    }

    public static OpenTelemetryBootstrap initialize(Function<String, String> configLookup) {
        Function<String, String> lookup = configLookup == null ? key -> null : configLookup;
        if (!isEnabled(lookup)) {
            return new OpenTelemetryBootstrap(false, OpenTelemetry.noop(), null);
        }
        if ("file".equalsIgnoreCase(trimmed(lookup.apply("PAICLI_OTEL_TRACES_EXPORTER")))) {
            return initializeFileExporter(lookup);
        }

        Map<String, String> properties = new LinkedHashMap<>();
        OTEL_PROPERTY_NAMES.forEach((environmentName, propertyName) -> {
            String value = trimmed(lookup.apply(environmentName));
            if (value != null) {
                properties.put(propertyName, value);
            }
        });
        properties.putIfAbsent("otel.service.name", "paicli");
        properties.putIfAbsent("otel.traces.exporter", "otlp");
        properties.putIfAbsent("otel.metrics.exporter", "none");
        properties.putIfAbsent("otel.logs.exporter", "none");
        properties.putIfAbsent("otel.exporter.otlp.traces.protocol", "http/protobuf");

        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> properties)
                .build()
                .getOpenTelemetrySdk();
        log.info("OpenTelemetry enabled: service={}, traceExporter={}",
                properties.getOrDefault("otel.service.name", "paicli"),
                properties.getOrDefault("otel.traces.exporter", "otlp"));
        return new OpenTelemetryBootstrap(true, sdk, sdk);
    }

    /**
     * 评测 harness 模式：span 逐条同步落盘为 JSONL，不经过 OTLP。
     * 配置：PAICLI_OTEL_TRACES_EXPORTER=file + PAICLI_OTEL_TRACES_FILE=<path>。
     */
    private static OpenTelemetryBootstrap initializeFileExporter(Function<String, String> lookup) {
        String file = trimmed(lookup.apply("PAICLI_OTEL_TRACES_FILE"));
        if (file == null) {
            log.warn("PAICLI_OTEL_TRACES_EXPORTER=file 但未配置 PAICLI_OTEL_TRACES_FILE，禁用 OpenTelemetry");
            return new OpenTelemetryBootstrap(false, OpenTelemetry.noop(), null);
        }
        try {
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(FileSpanExporter.create(Path.of(file))))
                    .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
            log.info("OpenTelemetry enabled: traceExporter=file, file={}", file);
            return new OpenTelemetryBootstrap(true, sdk, sdk);
        } catch (Exception e) {
            log.warn("file span exporter 初始化失败，禁用 OpenTelemetry: {}", file, e);
            return new OpenTelemetryBootstrap(false, OpenTelemetry.noop(), null);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    public Tracer tracer() {
        return openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    public PaiCliTracing tracing() {
        return tracing;
    }

    @Override
    public void close() {
        if (sdk == null || !closed.compareAndSet(false, true)) {
            return;
        }
        try {
            sdk.getSdkTracerProvider()
                    .forceFlush()
                    .join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("OpenTelemetry flush failed during shutdown", e);
        } finally {
            sdk.close();
        }
    }

    private static boolean isEnabled(Function<String, String> lookup) {
        String explicit = trimmed(lookup.apply("PAICLI_OTEL_ENABLED"));
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }
        if (Boolean.parseBoolean(trimmed(lookup.apply("OTEL_SDK_DISABLED")))) {
            return false;
        }
        return trimmed(lookup.apply("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")) != null
                || trimmed(lookup.apply("OTEL_EXPORTER_OTLP_ENDPOINT")) != null
                || ("file".equalsIgnoreCase(trimmed(lookup.apply("PAICLI_OTEL_TRACES_EXPORTER")))
                        && trimmed(lookup.apply("PAICLI_OTEL_TRACES_FILE")) != null);
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
