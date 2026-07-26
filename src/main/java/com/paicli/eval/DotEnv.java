package com.paicli.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取项目根 .env 供子进程继承。被测 PaiCLI 子进程的 cwd 是评测工作区，
 * 自己找不到项目根的 .env，API Key 等配置必须由 harness 注入环境变量。
 */
public final class DotEnv {

    private DotEnv() {
    }

    public static Map<String, String> read(Path envFile) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = stripQuotes(trimmed.substring(eq + 1).trim());
                if (!key.isEmpty()) {
                    entries.put(key, value);
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️ .env 读取失败，跳过注入: " + e.getMessage());
        }
        return entries;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
