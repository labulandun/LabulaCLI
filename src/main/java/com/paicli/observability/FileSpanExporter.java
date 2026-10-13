package com.paicli.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;

/**
 * 把 span 逐条追加为本地 JSONL（评测 harness 解析用，见 docs/evaluation-system-design.md §3.3）。
 * 必须配合 SimpleSpanProcessor 同步导出：评测子进程在 Windows 下被 destroy 时不会执行
 * shutdown hook，batch + flush 的方案会丢 span。
 */
final class FileSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(FileSpanExporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    private FileSpanExporter(Path file) {
        this.file = file;
    }

    static FileSpanExporter create(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        return new FileSpanExporter(file);
    }

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        StringBuilder lines = new StringBuilder();
        for (SpanData span : spans) {
            lines.append(toJsonLine(span)).append('\n');
        }
        try {
            Files.writeString(file, lines.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return CompletableResultCode.ofSuccess();
        } catch (IOException e) {
            log.warn("span 文件写入失败: {}", file, e);
            return CompletableResultCode.ofFailure();
        }
    }

    private static String toJsonLine(SpanData span) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("trace_id", span.getTraceId());
        node.put("span_id", span.getSpanId());
        if (span.getParentSpanContext().isValid()) {
            node.put("parent_span_id", span.getParentSpanContext().getSpanId());
        }
        node.put("name", span.getName());
        node.put("status", span.getStatus().getStatusCode().name());
        long startEpochMs = span.getStartEpochNanos() / 1_000_000;
        long endEpochMs = span.getEndEpochNanos() / 1_000_000;
        node.put("start_epoch_ms", startEpochMs);
        node.put("end_epoch_ms", endEpochMs);
        node.put("duration_ms", Math.max(0, endEpochMs - startEpochMs));
        ObjectNode attributes = node.putObject("attributes");
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), String.valueOf(value)));
        return node.toString();
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
