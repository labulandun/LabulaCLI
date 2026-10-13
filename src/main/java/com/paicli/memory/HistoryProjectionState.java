package com.paicli.memory;

/**
 * Immutable state carried between non-destructive history projections.
 *
 * <p>The observation fields prove that source changes since the previous successful projection
 * were append-only. They exclude the dynamic system prefix in the same way as the checkpoint
 * digest.</p>
 */
public record HistoryProjectionState(
        SummaryCheckpoint checkpoint,
        int observedSystemEnd,
        int observedSourceSize,
        String observedSourceDigest
) {
    private static final HistoryProjectionState EMPTY =
            new HistoryProjectionState(null, 0, 0, "");

    public HistoryProjectionState {
        observedSystemEnd = Math.max(0, observedSystemEnd);
        observedSourceSize = Math.max(observedSystemEnd, observedSourceSize);
        observedSourceDigest = observedSourceDigest == null ? "" : observedSourceDigest;
    }

    public static HistoryProjectionState empty() {
        return EMPTY;
    }

    public static HistoryProjectionState of(SummaryCheckpoint checkpoint) {
        return checkpoint == null
                ? EMPTY
                : new HistoryProjectionState(
                        checkpoint,
                        checkpoint.systemEnd(),
                        checkpoint.sourceSizeAtCheckpoint(),
                        checkpoint.sourceAppendBaselineDigest());
    }

    static HistoryProjectionState observed(SummaryCheckpoint checkpoint,
                                           int systemEnd,
                                           int sourceSize,
                                           String sourceDigest) {
        return checkpoint == null
                ? EMPTY
                : new HistoryProjectionState(checkpoint, systemEnd, sourceSize, sourceDigest);
    }

    public boolean hasCheckpoint() {
        return checkpoint != null;
    }
}
