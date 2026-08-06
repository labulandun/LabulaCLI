package com.paicli.eval;

import java.io.IOException;

/** Minimal judge abstraction so graders can be tested without network calls. */
public interface JudgeClient {

    String judge(String systemPrompt, String userPrompt) throws IOException;

    String provider();

    String model();

    default String cacheIdentity() {
        return provider() + "/" + model() + "/json-prompt-v1";
    }
}
