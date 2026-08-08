package com.paicli.runtime;

import com.paicli.config.ConfigValueResolver;

/** Central configuration for run deadlines and LLM resilience. */
public record ResilienceConfig(
        long agentDeadlineMillis,
        long stepDeadlineMillis,
        long finalizationReserveMillis,
        long llmAttemptTimeoutMillis,
        long llmFirstTokenTimeoutMillis,
        long llmConnectTimeoutMillis,
        long llmReadTimeoutMillis,
        long llmWriteTimeoutMillis,
        long llmCallTimeoutMillis,
        int llmMaxRetries,
        long llmRetryBaseDelayMillis,
        long llmRetryMaxDelayMillis,
        boolean circuitEnabled,
        int circuitFailureThreshold,
        long circuitWindowMillis,
        long circuitOpenMillis,
        int circuitHalfOpenProbes) {

    private static final ResilienceConfig INSTANCE = load();

    public static ResilienceConfig current() {
        return INSTANCE;
    }

    private static ResilienceConfig load() {
        return new ResilienceConfig(
                seconds("paicli.agent.deadline.seconds", "PAICLI_AGENT_DEADLINE_SECONDS", 600),
                seconds("paicli.agent.step.deadline.seconds", "PAICLI_AGENT_STEP_DEADLINE_SECONDS", 180),
                seconds("paicli.agent.finalization.reserve.seconds", "PAICLI_AGENT_FINALIZATION_RESERVE_SECONDS", 5),
                seconds("paicli.llm.attempt.timeout.seconds", "PAICLI_LLM_ATTEMPT_TIMEOUT_SECONDS", 180),
                seconds("paicli.llm.first.token.timeout.seconds", "PAICLI_LLM_FIRST_TOKEN_TIMEOUT_SECONDS", 60),
                seconds("paicli.llm.connect.timeout.seconds", "PAICLI_LLM_CONNECT_TIMEOUT_SECONDS", 60),
                seconds("paicli.llm.read.timeout.seconds", "PAICLI_LLM_READ_TIMEOUT_SECONDS", 300),
                seconds("paicli.llm.write.timeout.seconds", "PAICLI_LLM_WRITE_TIMEOUT_SECONDS", 60),
                seconds("paicli.llm.call.timeout.seconds", "PAICLI_LLM_CALL_TIMEOUT_SECONDS", 600),
                Math.min(3, ConfigValueResolver.nonNegativeInt(
                        "paicli.llm.max.retries", "PAICLI_LLM_MAX_RETRIES", 1)),
                Math.min(60_000, ConfigValueResolver.positiveLong(
                        "paicli.llm.retry.base.delay.millis", "PAICLI_LLM_RETRY_BASE_DELAY_MILLIS", 250)),
                Math.min(60_000, ConfigValueResolver.positiveLong(
                        "paicli.llm.retry.max.delay.millis", "PAICLI_LLM_RETRY_MAX_DELAY_MILLIS", 2_000)),
                ConfigValueResolver.bool("paicli.llm.circuit.enabled", "PAICLI_LLM_CIRCUIT_ENABLED", true),
                Math.min(100, Math.max(1, ConfigValueResolver.nonNegativeInt(
                        "paicli.llm.circuit.failure.threshold", "PAICLI_LLM_CIRCUIT_FAILURE_THRESHOLD", 3))),
                seconds("paicli.llm.circuit.window.seconds", "PAICLI_LLM_CIRCUIT_WINDOW_SECONDS", 60),
                seconds("paicli.llm.circuit.open.seconds", "PAICLI_LLM_CIRCUIT_OPEN_SECONDS", 30),
                Math.min(16, Math.max(1, ConfigValueResolver.nonNegativeInt(
                        "paicli.llm.circuit.half.open.probes", "PAICLI_LLM_CIRCUIT_HALF_OPEN_PROBES", 1)))
        );
    }

    private static long seconds(String propertyKey, String envKey, long defaultSeconds) {
        long value = ConfigValueResolver.positiveLong(propertyKey, envKey, defaultSeconds);
        try {
            return Math.multiplyExact(value, 1_000L);
        } catch (ArithmeticException ignored) {
            return Math.multiplyExact(defaultSeconds, 1_000L);
        }
    }
}
