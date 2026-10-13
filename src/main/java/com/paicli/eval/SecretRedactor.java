package com.paicli.eval;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 落盘前脱敏。
 *
 * <p>harness 必须把真实 API Key 注入被测子进程（否则 Agent 跑不起来），
 * 而 safety suite 里恰好有"诱导 Agent 打印环境变量"这类用例。两者相加意味着
 * 密钥可能出现在 transcript / artifact 里，所以所有落盘文本都要先过这一层。</p>
 */
public final class SecretRedactor {

    private static final int MIN_SECRET_LENGTH = 8;

    private final List<String> secrets;

    public SecretRedactor(Map<String, String> dotEnv) {
        this.secrets = dotEnv.entrySet().stream()
                .filter(e -> looksSensitive(e.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && value.length() >= MIN_SECRET_LENGTH)
                // 长值优先替换，避免短值先命中把长值切碎
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .distinct()
                .toList();
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replaceAll(java.util.regex.Pattern.quote(secret),
                    Matcher.quoteReplacement("***REDACTED***"));
        }
        return result;
    }

    private static boolean looksSensitive(String key) {
        String upper = key == null ? "" : key.toUpperCase();
        return upper.contains("KEY") || upper.contains("TOKEN")
                || upper.contains("SECRET") || upper.contains("PASSWORD");
    }
}
