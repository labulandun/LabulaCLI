package com.paicli.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void redactsSecretValuesFoundInArtifacts() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GLM_API_KEY", "sk-abcdef1234567890");
        env.put("DEEPSEEK_API_KEY", "sk-zzzzzzzzzzzzzzzz");

        SecretRedactor redactor = new SecretRedactor(env);
        String dumped = "GLM_API_KEY=sk-abcdef1234567890\nDEEPSEEK_API_KEY=sk-zzzzzzzzzzzzzzzz";
        String result = redactor.redact(dumped);

        assertFalse(result.contains("sk-abcdef1234567890"));
        assertFalse(result.contains("sk-zzzzzzzzzzzzzzzz"));
        assertTrue(result.contains("***REDACTED***"));
        // 变量名保留，便于判断模型是否真的把 env dump 出来了
        assertTrue(result.contains("GLM_API_KEY="));
    }

    @Test
    void ignoresNonSensitiveKeysAndShortValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OTEL_SERVICE_NAME", "paicli");
        env.put("GLM_MODEL", "glm-4");
        env.put("SOME_KEY", "short");

        SecretRedactor redactor = new SecretRedactor(env);
        assertEquals("service=paicli model=glm-4 short", redactor.redact("service=paicli model=glm-4 short"));
    }

    @Test
    void handlesNullEmptyAndRegexMetacharacters() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("API_KEY", "a+b(c)[d].*e");

        SecretRedactor redactor = new SecretRedactor(env);
        assertEquals("key=***REDACTED***", redactor.redact("key=a+b(c)[d].*e"));
        assertEquals("", redactor.redact(""));
        assertEquals(null, redactor.redact(null));
    }

    @Test
    void longerSecretsAreReplacedBeforeShorterOverlappingOnes() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SHORT_TOKEN", "abcdefgh");
        env.put("LONG_TOKEN", "abcdefgh-with-suffix");

        String result = new SecretRedactor(env).redact("value=abcdefgh-with-suffix");
        assertEquals("value=***REDACTED***", result);
    }
}
