package com.paicli.eval;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Adapts an existing PaiCLI LlmClient to the narrow JudgeClient contract. */
public final class LlmJudgeClient implements JudgeClient {

    private final LlmClient delegate;

    public LlmJudgeClient(LlmClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String judge(String systemPrompt, String userPrompt) throws IOException {
        LlmClient.ChatResponse response = delegate.chat(
                List.of(LlmClient.Message.system(systemPrompt), LlmClient.Message.user(userPrompt)),
                List.of());
        return response.content();
    }

    @Override
    public String provider() {
        return delegate.getProviderName();
    }

    @Override
    public String model() {
        return delegate.getModelName();
    }
}
