package com.paicli.observability;

import java.util.regex.Pattern;

/** Redacts common credentials and keeps exported span attributes bounded. */
final class TraceSanitizer {
    static final int MAX_ATTRIBUTE_CHARS = 16_384;
    private static final String TRUNCATED = "...[truncated]";
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:basic|bearer)\\s+)([^\\s,;\\\"}]+)");
    private static final Pattern LANGFUSE_KEY = Pattern.compile(
            "(?i)\\b(?:pk|sk)-lf-[a-z0-9_-]+");
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s,;\\\"}]+)");
    private static final Pattern BASE64_IMAGE = Pattern.compile(
            "(?i)data:image/[^;\\s]+;base64,[a-z0-9+/=\\r\\n]+");

    private TraceSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String sanitized = AUTHORIZATION.matcher(value).replaceAll("$1[REDACTED]");
        sanitized = LANGFUSE_KEY.matcher(sanitized).replaceAll("[REDACTED]");
        sanitized = API_KEY.matcher(sanitized).replaceAll("$1[REDACTED]");
        return BASE64_IMAGE.matcher(sanitized).replaceAll("[IMAGE_OMITTED]");
    }

    static String attribute(String value) {
        String sanitized = sanitize(value);
        if (sanitized.length() <= MAX_ATTRIBUTE_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_ATTRIBUTE_CHARS - TRUNCATED.length()) + TRUNCATED;
    }
}
