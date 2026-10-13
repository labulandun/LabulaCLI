package com.paicli.observability;

import com.paicli.llm.LlmClient;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PaiCLI 的业务埋点入口。未启用 OpenTelemetry 时由 no-op provider 保持原行为。
 */
public final class PaiCliTracing {
    static final String AGENT_SPAN_NAME = "paicli.react.run";
    static final String LLM_SPAN_NAME = "paicli.llm.chat";
    static final String ATTR_OBSERVATION_TYPE = "langfuse.observation.type";
    static final String ATTR_OBSERVATION_INPUT = "langfuse.observation.input";
    static final String ATTR_OBSERVATION_OUTPUT = "langfuse.observation.output";
    static final String ATTR_OBSERVATION_LEVEL = "langfuse.observation.level";
    static final String ATTR_STATUS_MESSAGE = "langfuse.observation.status_message";
    static final String ATTR_EXIT_REASON = "langfuse.observation.metadata.exit_reason";

    /**
     * ReAct 一轮运行的结束原因。评测时用它区分「答错」和「根本没跑完」，
     * 只有 {@link #COMPLETED} 才代表模型自己决定收敛。
     */
    public enum RunOutcome {
        COMPLETED("completed", "DEFAULT", false),
        CANCELLED("cancelled", "WARNING", false),
        TIMEOUT("timeout", "ERROR", true),
        BUDGET_EXHAUSTED("budget_exhausted", "ERROR", true),
        LLM_ERROR("llm_error", "ERROR", true),
        UNEXPECTED_ERROR("unexpected_error", "ERROR", true);

        private final String reason;
        private final String level;
        private final boolean errorStatus;

        RunOutcome(String reason, String level, boolean errorStatus) {
            this.reason = reason;
            this.level = level;
            this.errorStatus = errorStatus;
        }

        public String reason() {
            return reason;
        }
    }

    private final Tracer tracer;
    private final ThreadLocal<TraceReference> lastCompletedRun = new ThreadLocal<>();

    public PaiCliTracing(OpenTelemetry openTelemetry) {
        this.tracer = Objects.requireNonNullElse(openTelemetry, OpenTelemetry.noop())
                .getTracer("com.paicli");
    }

    public static PaiCliTracing noop() {
        return new PaiCliTracing(OpenTelemetry.noop());
    }

    public LlmClient traceLlmClient(LlmClient delegate) {
        if (delegate instanceof TracingLlmClient) {
            return delegate;
        }
        return new TracingLlmClient(delegate, tracer);
    }

    public AgentRun startAgentRun(String input, String provider, String model) {
        return startAgentRun(input, provider, model, null);
    }

    public AgentRun startAgentRun(String input, String provider, String model, String sessionId) {
        Span span = tracer.spanBuilder(AGENT_SPAN_NAME).startSpan();
        span.setAttribute(ATTR_OBSERVATION_TYPE, "agent");
        span.setAttribute("langfuse.trace.name", "paicli-react-run");
        span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), List.of("react"));
        span.setAttribute(ATTR_OBSERVATION_INPUT, TracePayloads.rootInput(input));
        setIfPresent(span, "langfuse.observation.metadata.provider", provider);
        setIfPresent(span, "langfuse.observation.metadata.model", model);
        setIfPresent(span, "langfuse.session.id", sessionId);
        return new AgentRun(span, span.makeCurrent());
    }

    public Optional<TraceReference> takeLastCompletedRun() {
        TraceReference reference = lastCompletedRun.get();
        lastCompletedRun.remove();
        return Optional.ofNullable(reference);
    }

    public ToolRun startTool(String name, String toolCallId, String argumentsJson) {
        String spanName = name == null || name.isBlank() ? "unknown-tool" : name;
        Span span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute(ATTR_OBSERVATION_TYPE, "tool");
        span.setAttribute(ATTR_OBSERVATION_INPUT, TraceSanitizer.attribute(argumentsJson == null ? "{}" : argumentsJson));
        setIfPresent(span, "langfuse.observation.metadata.tool_call_id", toolCallId);
        return new ToolRun(span, span.makeCurrent());
    }

    private static void setIfPresent(Span span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    /** 把 level / status_message / span status 一次写全，避免各处只写一半。 */
    private static void markLevel(Span span, String level, boolean errorStatus, String message) {
        span.setAttribute(ATTR_OBSERVATION_LEVEL, level);
        setIfPresent(span, ATTR_STATUS_MESSAGE, message);
        if (errorStatus) {
            span.setStatus(StatusCode.ERROR, message == null || message.isBlank() ? level : message);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record TraceReference(String traceId, String observationId) {
    }

    public final class AgentRun implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private boolean closed;
        private boolean outputSet;
        private RunOutcome outcome;

        private AgentRun(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public String traceId() {
            return span.getSpanContext().getTraceId();
        }

        public String observationId() {
            return span.getSpanContext().getSpanId();
        }

        public void output(String output) {
            span.setAttribute(ATTR_OBSERVATION_OUTPUT, TracePayloads.rootOutput(output));
            outputSet = true;
        }

        public void outputIfAbsent(String output) {
            if (!outputSet) {
                output(output);
            }
        }

        /** 记录非异常的提前结束（取消 / 预算耗尽等）；首个结果生效，后续调用忽略。 */
        public void outcome(RunOutcome outcome, String message) {
            if (this.outcome != null || outcome == null) {
                return;
            }
            this.outcome = outcome;
            span.setAttribute(ATTR_EXIT_REASON, outcome.reason);
            markLevel(span, outcome.level, outcome.errorStatus, message);
        }

        /** 记录异常结束：同时写 exit_reason、ERROR 状态与异常事件。 */
        public void error(RunOutcome outcome, Throwable error) {
            RunOutcome effective = outcome == null ? RunOutcome.UNEXPECTED_ERROR : outcome;
            if (this.outcome == null) {
                outcome(effective, describe(error));
            }
            if (error != null) {
                span.recordException(error);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            outcome(RunOutcome.COMPLETED, null);
            String traceId = traceId();
            String observationId = observationId();
            scope.close();
            span.end();
            if (span.getSpanContext().isValid()) {
                lastCompletedRun.set(new TraceReference(traceId, observationId));
            }
        }
    }

    public static final class ToolRun implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private boolean closed;

        private ToolRun(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void output(String output) {
            span.setAttribute(ATTR_OBSERVATION_OUTPUT, TraceSanitizer.attribute(output));
        }

        /** 工具被用户取消：记为 WARNING，不算工具本身失败。 */
        public void cancelled(String message) {
            markLevel(span, "WARNING", false, message);
        }

        public void error(Throwable error) {
            if (error == null) {
                return;
            }
            markLevel(span, "ERROR", true, describe(error));
            span.recordException(error);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            scope.close();
            span.end();
        }
    }
}
