package com.paicli.observability;

import com.paicli.llm.LlmClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TracingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final Tracer tracer;

    TracingLlmClient(LlmClient delegate, Tracer tracer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return trace(messages, () -> delegate.chat(messages, tools));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return trace(messages, () -> delegate.chat(messages, tools, listener));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    @Override
    public boolean replaysReasoningContent() {
        return delegate.replaysReasoningContent();
    }

    @Override
    public Map<String, Object> requestEnvelopeFields() {
        return delegate.requestEnvelopeFields();
    }

    private ChatResponse trace(List<Message> messages, IoCall call) throws IOException {
        Span span = tracer.spanBuilder(PaiCliTracing.LLM_SPAN_NAME).startSpan();
        span.setAttribute(PaiCliTracing.ATTR_OBSERVATION_TYPE, "generation");
        span.setAttribute("langfuse.observation.model.name", getModelName());
        span.setAttribute("langfuse.observation.metadata.provider", getProviderName());
        span.setAttribute(PaiCliTracing.ATTR_OBSERVATION_INPUT, TracePayloads.messages(messages));
        try (Scope ignored = span.makeCurrent()) {
            ChatResponse response = call.invoke();
            span.setAttribute(PaiCliTracing.ATTR_OBSERVATION_OUTPUT, TracePayloads.response(response));
            span.setAttribute("langfuse.observation.usage_details", TracePayloads.usage(response));
            return response;
        } catch (IOException | RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    private interface IoCall {
        ChatResponse invoke() throws IOException;
    }
}
