package com.paicli.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves runtime configuration without coupling low-level components to the CLI entry point.
 * Precedence: system property, environment variable, project .env, user ~/.env, default value.
 */
public final class ConfigValueResolver {
    private ConfigValueResolver() {
    }

    public static String resolve(String propertyKey, String envKey, String defaultValue) {
        String value = nonBlank(System.getProperty(propertyKey));
        if (value != null) {
            return value;
        }
        value = nonBlank(System.getenv(envKey));
        if (value != null) {
            return value;
        }
        value = readDotEnv(Path.of(".env"), envKey);
        if (value != null) {
            return value;
        }
        String userHome = nonBlank(System.getProperty("user.home"));
        value = userHome == null ? null : readDotEnv(Path.of(userHome, ".env"), envKey);
        return value == null ? defaultValue : value;
    }

    public static long positiveLong(String propertyKey, String envKey, long defaultValue) {
        String raw = resolve(propertyKey, envKey, Long.toString(defaultValue));
        try {
            long parsed = Long.parseLong(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static int nonNegativeInt(String propertyKey, String envKey, int defaultValue) {
        String raw = resolve(propertyKey, envKey, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(raw);
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static boolean bool(String propertyKey, String envKey, boolean defaultValue) {
        String raw = resolve(propertyKey, envKey, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        return defaultValue;
    }

    private static String readDotEnv(Path path, String key) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = line.trim();
                if (normalized.isEmpty() || normalized.startsWith("#")) {
                    continue;
                }
                if (normalized.startsWith("export ")) {
                    normalized = normalized.substring("export ".length()).trim();
                }
                int separator = normalized.indexOf('=');
                if (separator <= 0 || !key.equals(normalized.substring(0, separator).trim())) {
                    continue;
                }
                return unquote(normalized.substring(separator + 1).trim());
            }
        } catch (IOException ignored) {
            // Optional configuration files must not make startup fail.
        }
        return null;
    }

    private static String unquote(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return nonBlank(value);
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
