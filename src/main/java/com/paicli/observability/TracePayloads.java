package com.paicli.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TracePayloads {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TracePayloads() {
    }

    static String rootInput(String input) {
        return json(Map.of("input", TraceSanitizer.attribute(input)));
    }

    static String rootOutput(String output) {
        return json(Map.of("output", TraceSanitizer.attribute(output)));
    }

    static String messages(List<LlmClient.Message> messages) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (messages != null) {
            for (LlmClient.Message message : messages) {
                if (message == null) {
                    continue;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("role", message.role());
                value.put("content", TraceSanitizer.attribute(message.content()));
                if (message.toolCallId() != null) {
                    value.put("tool_call_id", message.toolCallId());
                }
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    value.put("tool_calls", toolCalls(message.toolCalls()));
                }
                values.add(value);
            }
        }
        return json(values);
    }

    static String response(LlmClient.ChatResponse response) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", response.role());
        value.put("content", TraceSanitizer.attribute(response.content()));
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            value.put("tool_calls", toolCalls(response.toolCalls()));
        }
        return json(value);
    }

    static String usage(LlmClient.ChatResponse response) {
        int cached = Math.max(0, response.cachedInputTokens());
        int input = Math.max(0, response.inputTokens() - cached);
        Map<String, Integer> usage = new LinkedHashMap<>();
        usage.put("input", input);
        if (cached > 0) {
            usage.put("input_cached_tokens", cached);
        }
        usage.put("output", Math.max(0, response.outputTokens()));
        usage.put("total", Math.max(0, response.inputTokens()) + Math.max(0, response.outputTokens()));
        return json(usage);
    }

    private static List<Map<String, Object>> toolCalls(List<LlmClient.ToolCall> calls) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (LlmClient.ToolCall call : calls) {
            if (call == null) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", call.id());
            if (call.function() != null) {
                value.put("name", call.function().name());
                value.put("arguments", TraceSanitizer.attribute(call.function().arguments()));
            }
            values.add(value);
        }
        return values;
    }

    private static String json(Object value) {
        try {
            String serialized = MAPPER.writeValueAsString(value);
            if (serialized.length() <= TraceSanitizer.MAX_ATTRIBUTE_CHARS) {
                return serialized;
            }
            int previewLength = Math.min(7_000, serialized.length());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("truncated", true);
            summary.put("original_chars", serialized.length());
            summary.put("preview", serialized.substring(0, previewLength));
            return MAPPER.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            return "\"<serialization-error>\"";
        }
    }
}
