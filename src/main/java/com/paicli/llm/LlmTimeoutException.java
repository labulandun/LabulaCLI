package com.paicli.llm;

import java.io.IOException;

public class LlmTimeoutException extends IOException {
    private final String phase;

    public LlmTimeoutException(String phase, String message) {
        super(message);
        this.phase = phase == null ? "unknown" : phase;
    }

    public LlmTimeoutException(String phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase == null ? "unknown" : phase;
    }

    public String phase() {
        return phase;
    }
}
