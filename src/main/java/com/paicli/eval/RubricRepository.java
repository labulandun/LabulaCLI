package com.paicli.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Loads immutable, versioned judge rubrics from eval/rubrics. */
public final class RubricRepository {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");
    private static final int MAX_RUBRIC_CHARS = 64 * 1024;

    private final Path root;

    public RubricRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Rubric load(String name) throws IOException {
        String normalizedName = name == null ? "" : name.strip();
        if (!SAFE_NAME.matcher(normalizedName).matches() || normalizedName.contains("..")) {
            throw new IllegalArgumentException("invalid rubric name: " + name);
        }
        Path file = root.resolve(normalizedName + ".md").normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("rubric path escapes root: " + name);
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("rubric does not exist: " + file);
        }
        String content = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            throw new IOException("rubric is empty: " + file);
        }
        if (content.length() > MAX_RUBRIC_CHARS) {
            throw new IOException("rubric is too large: " + file + " (" + content.length() + " chars)");
        }
        return new Rubric(normalizedName, content, sha256(content));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        }
    }

    public record Rubric(String name, String content, String sha256) {
    }
}
