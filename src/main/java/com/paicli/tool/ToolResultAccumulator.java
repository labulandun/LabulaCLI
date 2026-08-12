package com.paicli.tool;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;

/**
 * Bounded Plan fallback summary. It never concatenates full spilled artifacts.
 */
public final class ToolResultAccumulator {
    public static final int DEFAULT_MAX_CODE_POINTS = 24_000;
    private static final int MAX_INLINE_RESULT_CODE_POINTS = 3_000;

    private final int maxCodePoints;
    private final String manifestHandle;
    private final StringBuilder content = new StringBuilder();
    private boolean partial;
    private boolean hasArtifact;
    private int resultCount;

    public ToolResultAccumulator(String manifestHandle) {
        this(manifestHandle, DEFAULT_MAX_CODE_POINTS);
    }

    ToolResultAccumulator(String manifestHandle, int maxCodePoints) {
        this.manifestHandle = manifestHandle == null ? "" : manifestHandle;
        this.maxCodePoints = Math.max(512, maxCodePoints);
    }

    public void add(ToolExecutionResult result) {
        if (result == null) {
            return;
        }
        resultCount++;
        hasArtifact |= result.artifactRef() != null;
        String entry = formatEntry(result);
        appendBounded(entry);
    }

    public boolean isEmpty() {
        return resultCount == 0;
    }

    public String build() {
        if (isEmpty()) {
            return "";
        }
        String header = "[工具结果有界汇总]\n"
                + (!hasArtifact || manifestHandle.isBlank()
                ? ""
                : "artifact_manifest: " + manifestHandle + "\n")
                + "result_count: " + resultCount + "\n\n";
        String footer = partial
                ? hasArtifact
                ? "\n[汇总已达上限，partial: true；请通过 artifact_manifest/read_tool_result 按需读取]\n"
                : "\n[汇总已达上限，partial: true]\n"
                : "";
        String body = content.toString();
        int allowedBody = Math.max(0, maxCodePoints
                - codePoints(header)
                - codePoints(footer));
        body = ToolResultMetadata.truncateCodePoints(body, allowedBody);
        return header + body + footer;
    }

    private String formatEntry(ToolExecutionResult result) {
        StringBuilder entry = new StringBuilder();
        entry.append("## ").append(result.name()).append('\n');
        ToolResultArtifactRef ref = result.artifactRef();
        if (result.artifactWriteFailed()) {
            entry.append(ToolResultMetadata.truncateCodePoints(
                    result.result(), MAX_INLINE_RESULT_CODE_POINTS)).append('\n');
        } else if (ref != null) {
            entry.append("handle: ").append(ref.handle()).append('\n')
                    .append("original_chars: ").append(ref.chars()).append('\n')
                    .append("sha256: ").append(ref.sha256()).append('\n');
            String firstLine = result.resultMetadata().firstLine();
            if (!firstLine.isBlank()) {
                entry.append("summary: ").append(firstLine).append('\n');
            }
        } else {
            entry.append(ToolResultMetadata.truncateCodePoints(
                    result.displayPreview(), MAX_INLINE_RESULT_CODE_POINTS)).append('\n');
        }
        if (result.resultMetadata().partial()) {
            entry.append("partial: true\n");
        }
        entry.append('\n');
        return entry.toString();
    }

    private void appendBounded(String value) {
        if (partial || value == null || value.isEmpty()) {
            return;
        }
        int current = codePoints(content.toString());
        int remaining = Math.max(0, maxCodePoints - 512 - current);
        int valueLength = codePoints(value);
        if (valueLength <= remaining) {
            content.append(value);
            return;
        }
        if (remaining > 0) {
            content.append(ToolResultMetadata.truncateCodePoints(value, remaining));
        }
        partial = true;
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
