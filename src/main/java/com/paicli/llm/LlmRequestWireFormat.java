package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** Shared OpenAI-compatible request fragments used by transport and context accounting. */
public final class LlmRequestWireFormat {
    private LlmRequestWireFormat() {
    }

    public static ObjectNode requestEnvelope(ObjectMapper mapper, LlmClient client) {
        ObjectMapper json = mapper == null ? new ObjectMapper() : mapper;
        ObjectNode envelope = json.createObjectNode();
        envelope.put("model", client == null ? "" : nullToEmpty(client.getModelName()));
        envelope.put("stream", true);
        if (client != null) {
            Map<String, Object> fields = client.requestEnvelopeFields();
            if (fields != null) {
                fields.forEach((name, value) -> {
                    if (name != null && !name.isBlank() && value != null) {
                        envelope.set(name, json.valueToTree(value));
                    }
                });
            }
        }
        return envelope;
    }

    public static ArrayNode toolDefinitions(ObjectMapper mapper, List<LlmClient.Tool> tools) {
        ObjectMapper json = mapper == null ? new ObjectMapper() : mapper;
        ArrayNode toolArray = json.createArrayNode();
        if (tools == null) {
            return toolArray;
        }
        for (LlmClient.Tool tool : tools) {
            if (tool == null) {
                continue;
            }
            ObjectNode toolNode = toolArray.addObject();
            toolNode.put("type", "function");
            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", nullToEmpty(tool.name()));
            functionNode.put("description", nullToEmpty(tool.description()));
            JsonNode parameters = tool.parameters();
            functionNode.set("parameters", parameters == null ? json.createObjectNode() : parameters);
        }
        return toolArray;
    }

    /** Includes the top-level property name so accounting covers the actual wire wrapper. */
    public static ObjectNode toolsWireObject(ObjectMapper mapper, List<LlmClient.Tool> tools) {
        ObjectMapper json = mapper == null ? new ObjectMapper() : mapper;
        ObjectNode wire = json.createObjectNode();
        if (tools != null && !tools.isEmpty()) {
            wire.set("tools", toolDefinitions(json, tools));
        }
        return wire;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
