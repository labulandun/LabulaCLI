package com.paicli.context;

import java.util.Locale;

/** Immutable, estimated accounting for one concrete LLM request. */
public record RequestContextBudget(
        int maxContextWindow,
        long systemContentTokens,
        long userContentTokens,
        long assistantContentTokens,
        long toolMessageContentTokens,
        long imageTokens,
        long replayedReasoningTokens,
        long toolCallTokens,
        long messageFramingTokens,
        long toolsSchemaWireTokens,
        long requestEnvelopeTokens,
        int responseReserveTokens,
        int safetyMarginTokens,
        int softCompressionTriggerTokens,
        int hardInputCeilingTokens,
        int messageCount,
        int toolDefinitionCount
) {
    public long messageContentTokens() {
        return saturatedAdd(systemContentTokens, userContentTokens,
                assistantContentTokens, toolMessageContentTokens);
    }

    public long modelViewMessageTokens() {
        return saturatedAdd(messageContentTokens(), replayedReasoningTokens,
                toolCallTokens);
    }

    public long framingAndEnvelopeTokens() {
        return saturatedAdd(messageFramingTokens, requestEnvelopeTokens);
    }

    public long estimatedInputTokens() {
        return saturatedAdd(modelViewMessageTokens(), messageFramingTokens,
                toolsSchemaWireTokens, requestEnvelopeTokens);
    }

    public long budgetFootprintTokens() {
        return saturatedAdd(estimatedInputTokens(), responseReserveTokens, safetyMarginTokens);
    }

    public boolean needsProjection() {
        return estimatedInputTokens() >= softCompressionTriggerTokens;
    }

    public boolean safeToSend() {
        return estimatedInputTokens() <= hardInputCeilingTokens;
    }

    public long overHardCeilingTokens() {
        return Math.max(0L, estimatedInputTokens() - hardInputCeilingTokens);
    }

    public String diagnosticText() {
        return String.format(Locale.ROOT,
                "estimatedInput=%d, messageContent=%d, imagesIncluded=%d, reasoning=%d, "
                        + "toolCalls=%d, framing=%d, "
                        + "toolsSchema=%d, envelope=%d, responseReserve=%d, safety=%d, "
                        + "softTrigger=%d, hardCeiling=%d, window=%d",
                estimatedInputTokens(), messageContentTokens(), imageTokens,
                replayedReasoningTokens, toolCallTokens, messageFramingTokens, toolsSchemaWireTokens,
                requestEnvelopeTokens, responseReserveTokens, safetyMarginTokens,
                softCompressionTriggerTokens, hardInputCeilingTokens, maxContextWindow);
    }

    private static long saturatedAdd(long... values) {
        long total = 0L;
        for (long value : values) {
            if (value <= 0) {
                continue;
            }
            if (Long.MAX_VALUE - total < value) {
                return Long.MAX_VALUE;
            }
            total += value;
        }
        return total;
    }
}
