package com.paicli.tool;

/**
 * The bounded model-facing representation of a tool result.
 */
public record ToolResultProjection(
        String contextText,
        String displayPreview,
        ToolResultMetadata resultMetadata,
        ToolResultArtifactRef artifactRef,
        boolean spillAttempted,
        boolean artifactWriteFailed
) {
    public ToolResultProjection {
        contextText = contextText == null ? "" : contextText;
        displayPreview = displayPreview == null ? "" : displayPreview;
        resultMetadata = resultMetadata == null
                ? ToolResultMetadata.from("", displayPreview)
                : resultMetadata;
    }

    public boolean spilled() {
        return artifactRef != null;
    }

    public static ToolResultProjection inline(String toolName, String text) {
        String value = text == null ? "" : text;
        return new ToolResultProjection(
                value,
                value,
                ToolResultMetadata.from(toolName, value),
                null,
                false,
                false
        );
    }
}
