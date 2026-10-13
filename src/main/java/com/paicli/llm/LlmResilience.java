package com.paicli.llm;

import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.ResilienceConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** Budget-aware retry and provider/model/endpoint circuit breaker. */
final class LlmResilience {
    private static final Map<String, ProviderCircuitBreaker> CIRCUITS = new ConcurrentHashMap<>();

    private LlmResilience() {
    }

    static <T> T execute(String circuitKey, BooleanSupplier streamedOutput, Attempt<T> attempt) throws IOException {
        ResilienceConfig config = ResilienceConfig.current();
        ensureRunCanContinue();
        ProviderCircuitBreaker breaker = config.circuitEnabled()
                ? CIRCUITS.computeIfAbsent(circuitKey, ignored -> new ProviderCircuitBreaker())
                : null;
        CircuitPermit permit = breaker == null ? CircuitPermit.CLOSED : breaker.acquire(config);

        IOException lastFailure = null;
        for (int retry = 0; retry <= config.llmMaxRetries(); retry++) {
            try {
                ensureRunCanContinue();
                T result = attempt.run(retry + 1);
                if (breaker != null) {
                    breaker.success(permit);
                }
                return result;
            } catch (LlmCancelledException e) {
                if (breaker != null) {
                    breaker.release(permit);
                }
                throw e;
            } catch (IOException e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                if (!retryable) {
                    if (breaker != null) {
                        if (isLocalAbort(e)) {
                            breaker.release(permit);
                        } else {
                            breaker.ignored(permit);
                        }
                    }
                    throw e;
                }
                if (streamedOutput.getAsBoolean() || retry >= config.llmMaxRetries()) {
                    if (breaker != null) {
                        breaker.failure(permit, config);
                    }
                    throw e;
                }
                try {
                    sleepBeforeRetry(retry, e, config);
                } catch (IOException budgetFailure) {
                    if (breaker != null) {
                        if (countsAgainstCircuit(budgetFailure)) {
                            breaker.failure(permit, config);
                        } else if (isLocalAbort(budgetFailure)) {
                            breaker.release(permit);
                        } else {
                            breaker.ignored(permit);
                        }
                    }
                    throw budgetFailure;
                }
            } catch (RuntimeException e) {
                if (breaker != null) {
                    breaker.ignored(permit);
                }
                throw e;
            }
        }

        if (breaker != null) {
            breaker.failure(permit, config);
        }
        throw lastFailure == null
                ? new IOException("LLM 调用失败")
                : lastFailure;
    }

    private static void ensureRunCanContinue() throws IOException {
        CancellationToken token = CancellationContext.current();
        if (token == null || !token.isCancelled()) {
            return;
        }
        if (token.isDeadlineExceeded()) {
            throw new LlmTimeoutException("run", "Agent 全链路 Deadline 已耗尽");
        }
        throw new LlmCancelledException("LLM 调用已取消", null);
    }

    private static boolean isRetryable(IOException failure) {
        if (failure instanceof LlmTimeoutException timeout) {
            if ("run".equals(timeout.phase()) || "retry".equals(timeout.phase())) {
                return false;
            }
            return true;
        }
        if (failure instanceof LlmHttpException httpFailure) {
            return httpFailure.retryable();
        }
        return failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException
                || failure instanceof SocketException
                || failure instanceof SocketTimeoutException
                || failure instanceof EOFException
                || (failure instanceof InterruptedIOException && !Thread.currentThread().isInterrupted());
    }

    private static boolean countsAgainstCircuit(IOException failure) {
        if (failure instanceof LlmCancelledException) {
            return false;
        }
        if (failure instanceof LlmTimeoutException timeout) {
            return !"run".equals(timeout.phase()) && !"retry".equals(timeout.phase());
        }
        return isRetryable(failure);
    }

    private static boolean isLocalAbort(IOException failure) {
        if (failure instanceof LlmCancelledException) {
            return true;
        }
        if (failure instanceof LlmTimeoutException timeout) {
            return "run".equals(timeout.phase()) || "retry".equals(timeout.phase());
        }
        return failure instanceof InterruptedIOException && Thread.currentThread().isInterrupted();
    }

    private static void sleepBeforeRetry(int retry, IOException failure,
                                         ResilienceConfig config) throws IOException {
        long exponential = saturatedMultiply(config.llmRetryBaseDelayMillis(), 1L << Math.min(retry, 20));
        long cap = Math.min(exponential, config.llmRetryMaxDelayMillis());
        long delay = cap <= 1 ? cap : ThreadLocalRandom.current().nextLong(cap + 1);
        if (failure instanceof LlmHttpException httpFailure) {
            delay = Math.max(delay, Math.min(httpFailure.retryAfterMillis(), config.llmRetryMaxDelayMillis()));
        }

        ensureRunCanContinue();
        CancellationToken token = CancellationContext.current();
        if (token != null) {
            long remaining = token.remainingMillis();
            long reserve = config.finalizationReserveMillis();
            if (remaining <= reserve || remaining - reserve <= delay + 1_000) {
                throw new LlmTimeoutException("retry", "剩余 Deadline 不足以执行下一次 LLM 重试", failure);
            }
        }

        long slept = 0;
        while (slept < delay) {
            ensureRunCanContinue();
            long slice = Math.min(100, delay - slept);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ensureRunCanContinue();
                throw new LlmCancelledException("LLM 重试等待被中断", e);
            }
            slept += slice;
        }
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    @FunctionalInterface
    interface Attempt<T> {
        T run(int attemptNumber) throws IOException;
    }

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private record CircuitPermit(boolean halfOpen, long generation) {
        private static final CircuitPermit CLOSED = new CircuitPermit(false, -1);
    }

    private static final class ProviderCircuitBreaker {
        private final Deque<Long> failureTimes = new ArrayDeque<>();
        private CircuitState state = CircuitState.CLOSED;
        private long openUntilMillis;
        private int halfOpenInFlight;
        private long generation;

        private synchronized CircuitPermit acquire(ResilienceConfig config) throws LlmCircuitOpenException {
            long now = System.currentTimeMillis();
            if (state == CircuitState.OPEN && now >= openUntilMillis) {
                state = CircuitState.HALF_OPEN;
                halfOpenInFlight = 0;
                generation++;
            }
            if (state == CircuitState.OPEN) {
                throw new LlmCircuitOpenException("LLM Provider 熔断中，请在 "
                        + Math.max(1, openUntilMillis - now) + "ms 后重试");
            }
            if (state == CircuitState.HALF_OPEN) {
                if (halfOpenInFlight >= config.circuitHalfOpenProbes()) {
                    throw new LlmCircuitOpenException("LLM Provider 半开探针已占满，当前请求快速失败");
                }
                halfOpenInFlight++;
                return new CircuitPermit(true, generation);
            }
            trim(now, config.circuitWindowMillis());
            return CircuitPermit.CLOSED;
        }

        private synchronized void success(CircuitPermit permit) {
            if (permit.halfOpen() && state == CircuitState.HALF_OPEN
                    && permit.generation() == generation) {
                state = CircuitState.CLOSED;
                halfOpenInFlight = 0;
                failureTimes.clear();
            }
        }

        private synchronized void ignored(CircuitPermit permit) {
            if (permit.halfOpen() && state == CircuitState.HALF_OPEN
                    && permit.generation() == generation) {
                state = CircuitState.CLOSED;
                halfOpenInFlight = 0;
                failureTimes.clear();
            }
        }

        private synchronized void release(CircuitPermit permit) {
            if (permit.halfOpen() && state == CircuitState.HALF_OPEN
                    && permit.generation() == generation) {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            }
        }

        private synchronized void failure(CircuitPermit permit, ResilienceConfig config) {
            long now = System.currentTimeMillis();
            if (permit.halfOpen()) {
                if (state == CircuitState.HALF_OPEN && permit.generation() == generation) {
                    open(now, config.circuitOpenMillis());
                }
                return;
            }
            recordClosedFailure(now, config);
        }

        private void recordClosedFailure(long now, ResilienceConfig config) {
            trim(now, config.circuitWindowMillis());
            failureTimes.addLast(now);
            if (failureTimes.size() >= config.circuitFailureThreshold()) {
                open(now, config.circuitOpenMillis());
            }
        }

        private void open(long now, long openMillis) {
            state = CircuitState.OPEN;
            openUntilMillis = now + Math.max(1, openMillis);
            halfOpenInFlight = 0;
        }

        private void trim(long now, long windowMillis) {
            long cutoff = now - Math.max(1, windowMillis);
            while (!failureTimes.isEmpty() && failureTimes.peekFirst() < cutoff) {
                failureTimes.removeFirst();
            }
        }
    }
}
