package com.paicli.llm;

import java.io.IOException;

public class LlmCircuitOpenException extends IOException {
    public LlmCircuitOpenException(String message) {
        super(message);
    }
}
