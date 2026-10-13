package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析被测进程落盘的 spans.jsonl（FileSpanExporter 的输出格式）。 */
public final class SpanLog {

    /** type 取自 langfuse.observation.type：agent / generation / tool；其他 span 为空串。 */
    public record SpanRecord(String traceId,
                             String spanId,
                             String parentSpanId,
                             String name,
                             String type,
                             String status,
                             String level,
                             long durationMs,
                             Map<String, String> attributes) {

        public String attr(String key) {
            return attributes.getOrDefault(key, "");
        }

        public boolean isError() {
            return "ERROR".equals(status) || "ERROR".equals(level);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpanLog() {
    }

    /** 文件不存在返回空列表；单行损坏跳过该行（子进程被强杀时最后一行可能截断）。 */
    public static List<SpanRecord> read(Path spansFile) throws IOException {
        if (!Files.isRegularFile(spansFile)) {
            return List.of();
        }
        List<SpanRecord> records = new ArrayList<>();
        for (String line : Files.readAllLines(spansFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(parseLine(MAPPER.readTree(line)));
            } catch (IOException ignored) {
                // 截断的尾行：跳过
            }
        }
        return records;
    }

    private static SpanRecord parseLine(JsonNode node) {
        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode attrNode = node.path("attributes");
        attrNode.fieldNames().forEachRemaining(name -> attributes.put(name, attrNode.path(name).asText()));
        return new SpanRecord(
                node.path("trace_id").asText(""),
                node.path("span_id").asText(""),
                node.path("parent_span_id").asText(null),
                node.path("name").asText(""),
                attributes.getOrDefault("langfuse.observation.type", ""),
                node.path("status").asText("UNSET"),
                attributes.getOrDefault("langfuse.observation.level", ""),
                node.path("duration_ms").asLong(0),
                attributes);
    }
}
