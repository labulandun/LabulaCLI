package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.util.List;

/** Result of projecting source history into the immutable message list sent to a model. */
public record HistoryProjectionResult(
        List<LlmClient.Message> modelMessages,
        HistoryProjectionState nextState,
        boolean compacted,
        boolean checkpointAdvanced,
        int coveredFromInclusive,
        int coveredUntilExclusive,
        String coveredPrefixDigest,
        int estimatedTokensBefore,
        int estimatedTokensAfter,
        boolean summaryFailed
) {
    public HistoryProjectionResult {
        modelMessages = modelMessages == null ? List.of() : List.copyOf(modelMessages);
        nextState = nextState == null ? HistoryProjectionState.empty() : nextState;
        coveredFromInclusive = Math.max(0, coveredFromInclusive);
        coveredUntilExclusive = Math.max(coveredFromInclusive, coveredUntilExclusive);
        coveredPrefixDigest = coveredPrefixDigest == null ? "" : coveredPrefixDigest;
        estimatedTokensBefore = Math.max(0, estimatedTokensBefore);
        estimatedTokensAfter = Math.max(0, estimatedTokensAfter);
    }

    /** Compatibility alias used by the Phase 90 design document. */
    public List<LlmClient.Message> modelView() {
        return modelMessages;
    }
}
