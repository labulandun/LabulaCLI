package com.paicli.tool;

import java.time.Instant;

/**
 * Stable metadata for a tool result persisted outside the model conversation.
 */
public record ToolResultArtifactRef(
        String artifactId,
        String handle,
        String projectHash,
        String sessionId,
        String toolName,
        String toolCallId,
        Instant createdAt,
        int chars,
        long bytes,
        String sha256,
        boolean capturedComplete,
        boolean unicodeNormalized
) {
}
