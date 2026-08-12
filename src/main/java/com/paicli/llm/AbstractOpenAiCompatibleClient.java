package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.ResilienceConfig;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();

    // The builder created per attempt narrows these configured caps with the current run deadline.
    // newBuilder() keeps the shared dispatcher/connection pool while allowing per-call budgets.
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder().build();
    private static final ScheduledThreadPoolExecutor FIRST_TOKEN_WATCHDOG = createFirstTokenWatchdog();

    private static ScheduledThreadPoolExecutor createFirstTokenWatchdog() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "paicli-llm-first-token-timeout");
                thread.setDaemon(true);
                return thread;
            });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    /**
     * Compatibility hook for provider implementations created before the capability was exposed
     * on {@link LlmClient}. New callers should use {@link #replaysReasoningContent()}.
     */
    @Deprecated
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    @Override
    public boolean replaysReasoningContent() {
        return shouldSendReasoningContentInRequestHistory();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        AtomicBoolean streamedOutput = new AtomicBoolean(false);
        StreamListener trackingListener = new StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                if (delta != null && !delta.isEmpty()) {
                    streamedOutput.set(true);
                }
                streamListener.onReasoningDelta(delta);
            }

            @Override
            public void onContentDelta(String delta) {
                if (delta != null && !delta.isEmpty()) {
                    streamedOutput.set(true);
                }
                streamListener.onContentDelta(delta);
            }
        };
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        String circuitKey = getProviderName() + "|" + getModelName() + "|" + circuitEndpoint(getApiUrl());
        return LlmResilience.execute(circuitKey, streamedOutput::get,
                attempt -> executeAttempt(request, trackingListener, streamedOutput));
    }

    private ChatResponse executeAttempt(Request request, StreamListener streamListener,
                                        AtomicBoolean streamedOutput) throws IOException {
        ResilienceConfig config = ResilienceConfig.current();
        long configuredCap = Math.min(config.llmAttemptTimeoutMillis(), config.llmCallTimeoutMillis());
        long attemptBudget = CancellationContext.remainingMillis(
                configuredCap, config.finalizationReserveMillis());
        if (attemptBudget <= 0) {
            throw new LlmTimeoutException("run", "Agent 剩余 Deadline 不足以启动 LLM 调用");
        }

        OkHttpClient attemptClient = SHARED_HTTP_CLIENT.newBuilder()
                .connectTimeout(Math.max(1, Math.min(config.llmConnectTimeoutMillis(), attemptBudget)),
                        TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1, Math.min(config.llmReadTimeoutMillis(), attemptBudget)),
                        TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1, Math.min(config.llmWriteTimeoutMillis(), attemptBudget)),
                        TimeUnit.MILLISECONDS)
                .callTimeout(Math.max(1, attemptBudget), TimeUnit.MILLISECONDS)
                .build();

        Call call = attemptClient.newCall(request);
        CancellationToken token = CancellationContext.current();
        CancellationToken.Registration cancellationRegistration = token == null
                ? CancellationToken.Registration.NO_OP
                : token.onCancel(call::cancel);
        AtomicReference<String> forcedTimeoutPhase = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> firstTokenTask = new AtomicReference<>();
        long firstTokenBudget = Math.max(1, Math.min(config.llmFirstTokenTimeoutMillis(), attemptBudget));
        firstTokenTask.set(FIRST_TOKEN_WATCHDOG.schedule(() -> {
            forcedTimeoutPhase.compareAndSet(null, "first_token");
            call.cancel();
        }, firstTokenBudget, TimeUnit.MILLISECONDS));

        Runnable markFirstEvent = () -> {
            ScheduledFuture<?> task = firstTokenTask.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        };

        try (Response response = call.execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new LlmHttpException(response.code(),
                        "API请求失败: " + response.code() + " - " + truncate(errorBody, 2_000),
                        retryAfterMillis(response.header("Retry-After")));
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }

            BufferedSource source = responseBodyObj.source();
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;

            while (!source.exhausted()) {
                ensureNotCancelled();
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    markFirstEvent.run();
                    break;
                }

                JsonNode root = mapper.readTree(payload);
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens);
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                JsonNode toolCallDeltas = delta.path("tool_calls");
                if (!extractReasoningDelta(delta).isEmpty()
                        || !delta.path("content").asText("").isEmpty()
                        || (toolCallDeltas.isArray() && !toolCallDeltas.isEmpty())) {
                    markFirstEvent.run();
                }
                if (toolCallDeltas.isArray() && !toolCallDeltas.isEmpty()) {
                    streamedOutput.set(true);
                }

                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) {
                    role = deltaRole;
                }

                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    streamListener.onReasoningDelta(reasoningDelta);
                }

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    streamListener.onContentDelta(contentDelta);
                }

                mergeToolCallDeltas(toolAccumulators, toolCallDeltas);
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    buildToolCalls(toolAccumulators),
                    inputTokens,
                    outputTokens,
                    cachedInputTokens
            );
        } catch (LlmHttpException | LlmTimeoutException | LlmCancelledException e) {
            throw e;
        } catch (IOException e) {
            if (token != null && token.isCancelled()) {
                if (token.isDeadlineExceeded()) {
                    throw new LlmTimeoutException("run", "Agent 全链路 Deadline 已耗尽", e);
                }
                throw new LlmCancelledException("LLM 调用已主动取消", e);
            }
            String forcedPhase = forcedTimeoutPhase.get();
            if (forcedPhase != null) {
                throw new LlmTimeoutException(forcedPhase,
                        "LLM 首 Token 等待超过 " + firstTokenBudget + "ms", e);
            }
            if (e instanceof SocketTimeoutException) {
                throw new LlmTimeoutException("stream_idle", "LLM 流式响应静默超时", e);
            }
            if (e instanceof InterruptedIOException
                    && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("timeout")) {
                throw new LlmTimeoutException("attempt", "LLM 单次调用超时", e);
            }
            throw e;
        } finally {
            markFirstEvent.run();
            cancellationRegistration.close();
            call.cancel();
        }
    }

    private static String circuitEndpoint(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = url.length();
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return url.substring(0, end);
    }

    private static void ensureNotCancelled() throws IOException {
        CancellationToken token = CancellationContext.current();
        if (token == null || !token.isCancelled()) {
            return;
        }
        if (token.isDeadlineExceeded()) {
            throw new LlmTimeoutException("run", "Agent 全链路 Deadline 已耗尽");
        }
        throw new LlmCancelledException("LLM 调用已取消", null);
    }

    private static long retryAfterMillis(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.multiplyExact(Long.parseLong(value.trim()), 1_000L);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return 0;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            appendMessageContent(msgNode, msg);
            if (replaysReasoningContent()
                    && "assistant".equals(msg.role())
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        customizeRequestBody(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.id,
                    new ToolCall.Function(acc.name.toString(), acc.arguments.toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
