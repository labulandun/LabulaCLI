package com.paicli.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bounded, structured facts derived before a potentially large raw result is released.
 */
public record ToolResultMetadata(
        boolean partial,
        boolean producerTruncated,
        boolean stepSearch,
        boolean failed,
        String firstLine,
        String sourceLabel,
        int resultCount,
        String title,
        int contentChars
) {
    private static final int MAX_FIELD_CODE_POINTS = 240;
    private static final int MAX_INSPECTION_CODE_POINTS = 64_000;
    private static final Pattern JSON_PARTIAL = Pattern.compile("\\\"partial\\\"\\s*:\\s*true");
    private static final Pattern JSON_TRUNCATED = Pattern.compile("\\\"truncated\\\"\\s*:\\s*true");

    public ToolResultMetadata {
        firstLine = firstLine == null ? "" : firstLine;
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
        title = title == null ? "" : title;
        resultCount = Math.max(0, resultCount);
        contentChars = Math.max(0, contentChars);
    }

    public static ToolResultMetadata from(String toolName, String text) {
        String value = text == null ? "" : text;
        int contentChars = value.codePointCount(0, value.length());
        String inspected = inspectionSample(value, contentChars);
        String lower = inspected.toLowerCase(Locale.ROOT);
        String firstLine = inspected.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        boolean stepSearch = inspected.contains("[StepSearch]");
        boolean partial = lower.contains("partial: true") || JSON_PARTIAL.matcher(lower).find();
        boolean producerTruncated = partial
                || lower.contains("truncated: true")
                || JSON_TRUNCATED.matcher(lower).find()
                || lower.contains("output truncated")
                || inspected.contains("已截断")
                || inspected.contains("部分结果");
        boolean failed = lower.startsWith("error")
                || lower.startsWith("failed")
                || lower.contains("tool execution failed")
                || inspected.contains("工具执行失败")
                || inspected.contains("搜索失败")
                || inspected.contains("抓取失败")
                || firstLine.startsWith("❌")
                || firstLine.startsWith("⚠");
        int resultCount = "web_search".equals(toolName)
                ? (int) inspected.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count()
                : 0;
        String title = inspected.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("📄 标题:")
                        || line.startsWith("📋 标题:")
                        || line.toLowerCase(Locale.ROOT).startsWith("title:"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .findFirst()
                .orElse("");
        String source = stepSearch ? "StepSearch" : (toolName == null ? "" : toolName);
        return new ToolResultMetadata(
                partial,
                producerTruncated,
                stepSearch,
                failed,
                truncateCodePoints(firstLine, MAX_FIELD_CODE_POINTS),
                truncateCodePoints(source, MAX_FIELD_CODE_POINTS),
                resultCount,
                truncateCodePoints(title, MAX_FIELD_CODE_POINTS),
                contentChars
        );
    }

    private static String inspectionSample(String value, int codePoints) {
        if (codePoints <= MAX_INSPECTION_CODE_POINTS) {
            return value;
        }
        int half = MAX_INSPECTION_CODE_POINTS / 2;
        int headEnd = value.offsetByCodePoints(0, half);
        int tailStart = value.offsetByCodePoints(0, codePoints - half);
        return value.substring(0, headEnd) + "\n" + value.substring(tailStart);
    }

    static String truncateCodePoints(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
