package com.paicli.tool;

import java.util.List;

/** Bounded, immutable metadata used to append a recoverability index to a model view. */
public record ToolResultArtifactIndex(
        String manifestHandle,
        int artifactCount,
        int manifestSchemaVersion,
        List<ToolResultArtifactRef> recentArtifacts
) {
    public static final int CURRENT_MANIFEST_SCHEMA_VERSION = 1;
    public static final int MAX_RECENT_ARTIFACTS = 20;
    private static final ToolResultArtifactIndex EMPTY =
            new ToolResultArtifactIndex("", 0, CURRENT_MANIFEST_SCHEMA_VERSION, List.of());

    public ToolResultArtifactIndex {
        manifestHandle = manifestHandle == null ? "" : manifestHandle;
        artifactCount = Math.max(0, artifactCount);
        manifestSchemaVersion = Math.max(0, manifestSchemaVersion);
        recentArtifacts = recentArtifacts == null
                ? List.of()
                : recentArtifacts.stream()
                        .filter(java.util.Objects::nonNull)
                        .limit(MAX_RECENT_ARTIFACTS)
                        .toList();
    }

    public static ToolResultArtifactIndex empty() {
        return EMPTY;
    }

    public boolean hasArtifacts() {
        return artifactCount > 0 && !manifestHandle.isBlank();
    }
}
