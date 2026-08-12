package com.paicli.memory;

import java.util.Objects;

/**
 * Immutable checkpoint for a summarized prefix of source conversation history.
 *
 * <p>The covered range uses source-history indexes. The dynamic system message at index {@code 0}
 * is deliberately outside the digest and is carried directly into every model view.</p>
 */
public record SummaryCheckpoint(
        int systemEnd,
        int coveredUntilExclusive,
        String coveredPrefixDigest,
        int sourceSizeAtCheckpoint,
        String sourceAppendBaselineDigest,
        String summary,
        String summaryPolicyVersion,
        int retainRecentRounds,
        String provider,
        String model,
        boolean reasoningReplayEnabled,
        String artifactManifestHandle,
        int artifactManifestSchemaVersion
) {
    public SummaryCheckpoint {
        if (systemEnd < 0 || coveredUntilExclusive <= systemEnd
                || sourceSizeAtCheckpoint < coveredUntilExclusive) {
            throw new IllegalArgumentException("covered prefix must be non-empty");
        }
        coveredPrefixDigest = Objects.requireNonNull(coveredPrefixDigest, "coveredPrefixDigest");
        sourceAppendBaselineDigest = Objects.requireNonNull(
                sourceAppendBaselineDigest, "sourceAppendBaselineDigest");
        summary = Objects.requireNonNull(summary, "summary");
        summaryPolicyVersion = Objects.requireNonNull(summaryPolicyVersion, "summaryPolicyVersion");
        retainRecentRounds = Math.max(1, retainRecentRounds);
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        artifactManifestHandle = artifactManifestHandle == null ? "" : artifactManifestHandle;
        artifactManifestSchemaVersion = Math.max(0, artifactManifestSchemaVersion);
    }
}
