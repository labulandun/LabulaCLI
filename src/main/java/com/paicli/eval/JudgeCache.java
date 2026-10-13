package com.paicli.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Content-addressed, concurrency-safe cache for judge decisions. Prompts are never persisted. */
public final class JudgeCache {

    static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public JudgeCache(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Optional<Entry> get(String key) throws IOException {
        validateKey(key);
        Path file = root.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        int score = node.path("score").asInt(0);
        String reason = node.path("reason").asText("");
        if (node.path("schema_version").asInt(0) != SCHEMA_VERSION
                || !key.equals(node.path("key").asText())
                || score < 1 || score > 5
                || reason.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Entry(
                score,
                reason,
                node.path("provider").asText("unknown"),
                node.path("model").asText("unknown"),
                node.path("rubric").asText("unknown")));
    }

    public Lookup getOrCompute(String key, Loader loader) throws Exception {
        validateKey(key);
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Optional<Entry> cached = get(key);
            if (cached.isPresent()) {
                return new Lookup(cached.get(), true);
            }
            Entry loaded = loader.load();
            validateEntry(loaded);
            write(key, loaded);
            return new Lookup(loaded, false);
        }
    }

    private void write(String key, Entry entry) throws IOException {
        Files.createDirectories(root);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schema_version", SCHEMA_VERSION);
        node.put("key", key);
        node.put("score", entry.score());
        node.put("reason", entry.reason());
        node.put("provider", entry.provider());
        node.put("model", entry.model());
        node.put("rubric", entry.rubric());

        Path temp = Files.createTempFile(root, key + ".", ".tmp");
        try {
            Files.writeString(temp, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.move(temp, root.resolve(key + ".json"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, root.resolve(key + ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid judge cache key");
        }
    }

    private static void validateEntry(Entry entry) {
        if (entry == null || entry.score() < 1 || entry.score() > 5
                || entry.reason() == null || entry.reason().isBlank()) {
            throw new IllegalArgumentException("invalid judge cache entry");
        }
    }

    @FunctionalInterface
    public interface Loader {
        Entry load() throws Exception;
    }

    public record Entry(int score, String reason, String provider, String model, String rubric) {
    }

    public record Lookup(Entry entry, boolean hit) {
    }
}
