package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;

/** case.json 中 graders 数组的一项：type 决定实现，其余字段由对应 Grader 自行解读。 */
public record GraderSpec(String type, JsonNode params) {

    public String text(String field, String defaultValue) {
        JsonNode node = params.path(field);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
    }

    public int intValue(String field, int defaultValue) {
        return params.path(field).asInt(defaultValue);
    }
}
