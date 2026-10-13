package com.paicli.llm;

import java.io.IOException;

public class LlmCancelledException extends IOException {
    public LlmCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
