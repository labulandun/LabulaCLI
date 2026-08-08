package com.paicli.llm;

import java.io.IOException;

/** HTTP failure with enough structure for retry and circuit-breaker classification. */
public class LlmHttpException extends IOException {
    private final int statusCode;
    private final long retryAfterMillis;

    public LlmHttpException(int statusCode, String message, long retryAfterMillis) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public int statusCode() {
        return statusCode;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean retryable() {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }
}
