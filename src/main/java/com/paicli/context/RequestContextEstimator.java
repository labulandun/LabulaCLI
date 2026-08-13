package com.paicli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmRequestWireFormat;
import com.paicli.memory.MemoryEntry;

import java.util.List;
import java.util.Objects;

/**
 * Phase 90.3 request-level estimator. It is deliberately heuristic, but every request path uses
 * the same components and the configured safety margin covers tokenizer/JSON-shape error.
 */
public final class RequestContextEstimator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BASE_MESSAGE_FRAMING_TOKENS = 4;
    private static final int MESSAGES_FIELD_FRAMING_TOKENS = 3;
    private static final int CONTENT_PART_FRAMING_TOKENS = 4;
    private static final int TOOL_CALL_FRAMING_TOKENS = 8;
    private static final int TOOL_CALL_ID_FRAMING_TOKENS = 2;

    private final LlmClient llmClient;
    private final ContextProfile profile;

    public RequestContextEstimator(LlmClient llmClient, ContextProfile profile) {
        this.llmClient = llmClient;
        this.profile = profile == null ? ContextProfile.from(llmClient) : profile;
    }

    public static RequestContextEstimator forClient(LlmClient llmClient) {
        return new RequestContextEstimator(llmClient, ContextProfile.from(llmClient));
    }

    public ContextProfile profile() {
        return profile;
    }

    public RequestContextBudget estimate(List<LlmClient.Message> messages,
                                         List<LlmClient.Tool> tools) {
        long system = 0L;
        long user = 0L;
        long assistant = 0L;
        long toolMessage = 0L;
        long imageTokens = 0L;
        long reasoning = 0L;
        long toolCalls = 0L;
        long framing = messages == null || messages.isEmpty()
                ? 0L
                : MESSAGES_FIELD_FRAMING_TOKENS;
        int messageCount = 0;

        if (messages != null) {
            for (LlmClient.Message message : messages) {
                if (message == null) {
                    continue;
                }
                messageCount++;
                long contentTokens = estimateContent(message);
                long messageImages = estimateImages(message);
                imageTokens = add(imageTokens, messageImages);
                switch (message.role() == null ? "" : message.role()) {
                    case "system" -> system = add(system, contentTokens);
                    case "user" -> user = add(user, contentTokens);
                    case "assistant" -> assistant = add(assistant, contentTokens);
                    case "tool" -> toolMessage = add(toolMessage, contentTokens);
                    default -> user = add(user, contentTokens);
                }

                framing = add(framing, BASE_MESSAGE_FRAMING_TOKENS);
                if (message.contentParts() != null) {
                    framing = add(framing,
                            (long) message.contentParts().size() * CONTENT_PART_FRAMING_TOKENS);
                }
                if (replaysReasoning(message)) {
                    reasoning = add(reasoning, estimateText(message.reasoningContent()));
                }
                if (message.toolCalls() != null) {
                    for (LlmClient.ToolCall call : message.toolCalls()) {
                        if (call == null) {
                            continue;
                        }
                        framing = add(framing, TOOL_CALL_FRAMING_TOKENS);
                        toolCalls = add(toolCalls, estimateText(call.id()));
                        LlmClient.ToolCall.Function function = call.function();
                        if (function != null) {
                            toolCalls = add(toolCalls, estimateText(function.name()));
                            toolCalls = add(toolCalls, estimateText(function.arguments()));
                        }
                    }
                }
                if (message.toolCallId() != null) {
                    framing = add(framing, TOOL_CALL_ID_FRAMING_TOKENS);
                    toolCalls = add(toolCalls, estimateText(message.toolCallId()));
                }
            }
        }

        long toolsSchema = tools == null || tools.isEmpty()
                ? 0L
                : estimateText(LlmRequestWireFormat.toolsWireObject(MAPPER, tools).toString());
        long envelope = estimateText(LlmRequestWireFormat.requestEnvelope(MAPPER, llmClient).toString());
        int toolCount = tools == null ? 0 : (int) tools.stream().filter(Objects::nonNull).count();

        return new RequestContextBudget(
                profile.maxContextWindow(),
                system,
                user,
                assistant,
                toolMessage,
                imageTokens,
                reasoning,
                toolCalls,
                framing,
                toolsSchema,
                envelope,
                profile.responseReserveTokens(),
                profile.safetyMarginTokens(),
                profile.compressionTriggerTokens(),
                profile.hardInputCeilingTokens(),
                messageCount,
                toolCount);
    }

    public RequestContextBudget validate(List<LlmClient.Message> messages,
                                         List<LlmClient.Tool> tools)
            throws ContextViewBuildException {
        RequestContextBudget budget = estimate(messages, tools);
        if (!budget.safeToSend()) {
            throw new ContextViewBuildException(budget);
        }
        return budget;
    }

    private boolean replaysReasoning(LlmClient.Message message) {
        return llmClient != null
                && llmClient.replaysReasoningContent()
                && "assistant".equals(message.role())
                && message.reasoningContent() != null
                && !message.reasoningContent().isBlank();
    }

    private static long estimateContent(LlmClient.Message message) {
        if (!message.hasContentParts()) {
            return estimateText(message.content());
        }
        long total = 0L;
        boolean serializedPart = false;
        for (LlmClient.ContentPart part : message.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText() && part.text() != null && !part.text().isBlank()) {
                total = add(total, estimateText(part.text()));
                serializedPart = true;
            } else if (hasSerializableImage(part)) {
                total = add(total, estimateImageTokens(part));
                serializedPart = true;
            }
        }
        // Keep the same fallback as AbstractOpenAiCompatibleClient.appendMessageContent().
        return serializedPart ? total : estimateText(message.content());
    }

    private static long estimateImages(LlmClient.Message message) {
        if (message.contentParts() == null) {
            return 0L;
        }
        long total = 0L;
        for (LlmClient.ContentPart part : message.contentParts()) {
            if (hasSerializableImage(part)) {
                total = add(total, estimateImageTokens(part));
            }
        }
        return total;
    }

    private static int estimateImageTokens(LlmClient.ContentPart part) {
        if (part.imageBase64() != null && !part.imageBase64().isBlank()) {
            long bytes = part.imageBase64().length() * 3L / 4L;
            return (int) Math.max(256L, Math.min(4_096L, bytes / 768L));
        }
        return 1_024;
    }

    private static boolean hasSerializableImage(LlmClient.ContentPart part) {
        if (part == null || !part.isImage()) {
            return false;
        }
        if ("image_url".equals(part.type())) {
            return part.imageUrl() != null && !part.imageUrl().isBlank();
        }
        return part.imageBase64() != null && !part.imageBase64().isBlank();
    }

    private static int estimateText(String value) {
        return MemoryEntry.estimateTokens(value);
    }

    private static long add(long left, long right) {
        if (right <= 0) {
            return left;
        }
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
