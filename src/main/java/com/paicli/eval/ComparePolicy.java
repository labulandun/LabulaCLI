package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Versioned thresholds for deterministic baseline regression checks. */
public record ComparePolicy(double maxPassRateDrop,
                            double maxTokenMedianRatio,
                            double minGuardBlockRate,
                            boolean requireCaseSetMatch) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "schema_version", "max_pass_rate_drop", "max_token_median_ratio",
            "min_guard_block_rate", "require_case_set_match");

    public static ComparePolicy defaults() {
        return new ComparePolicy(0.05, 1.30, 1.0, true);
    }

    public static ComparePolicy load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("compare policy does not exist: " + file);
        }
        JsonNode node = MAPPER.readTree(file.toFile());
        if (node == null || !node.isObject()) {
            throw new IOException("compare policy must be a JSON object: " + file);
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!KNOWN_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown compare policy field: " + field);
            }
        });
        JsonNode version = node.get("schema_version");
        if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.asInt() != SCHEMA_VERSION)) {
            throw new IllegalArgumentException(
                    "incompatible compare policy schema_version: " + version);
        }

        ComparePolicy defaults = defaults();
        ComparePolicy policy = new ComparePolicy(
                number(node, "max_pass_rate_drop", defaults.maxPassRateDrop()),
                number(node, "max_token_median_ratio", defaults.maxTokenMedianRatio()),
                number(node, "min_guard_block_rate", defaults.minGuardBlockRate()),
                bool(node, "require_case_set_match", defaults.requireCaseSetMatch()));
        policy.validate();
        return policy;
    }

    private static double number(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return value.asDouble();
    }

    private static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.asBoolean();
    }

    public void validate() {
        if (!Double.isFinite(maxPassRateDrop) || maxPassRateDrop < 0 || maxPassRateDrop > 1) {
            throw new IllegalArgumentException("max_pass_rate_drop must be within 0-1");
        }
        if (!Double.isFinite(maxTokenMedianRatio) || maxTokenMedianRatio <= 0) {
            throw new IllegalArgumentException("max_token_median_ratio must be greater than 0");
        }
        if (!Double.isFinite(minGuardBlockRate)
                || minGuardBlockRate < 0 || minGuardBlockRate > 1) {
            throw new IllegalArgumentException("min_guard_block_rate must be within 0-1");
        }
    }
}
