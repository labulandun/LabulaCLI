package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.config.ConfigValueResolver;
import com.paicli.memory.MemoryEntry;
import com.paicli.runtime.CancellationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session-scoped storage for recoverable, model-facing tool result artifacts.
 *
 * <p>The store deliberately exposes opaque handles only. Paths and raw tool arguments are never
 * included in the model envelope or artifact metadata.</p>
 */
public final class ToolResultArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ToolResultArtifactStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern HANDLE_PATTERN = Pattern.compile(
            "^tool-result://([A-Za-z0-9-]+)/([A-Za-z0-9_-]+)$");

    public static final int DEFAULT_SPILL_THRESHOLD_TOKENS = 4_096;
    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final int DEFAULT_READ_CODE_POINTS = 12_000;
    public static final int MAX_READ_CODE_POINTS = 24_000;
    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L;
    public static final long DEFAULT_MAX_SESSION_BYTES = 256L * 1024L * 1024L;

    private static final int PREVIEW_HEAD_CODE_POINTS = 4_000;
    private static final int PREVIEW_TAIL_CODE_POINTS = 1_000;
    private static final int MAX_SESSION_ARTIFACTS = 4_096;
    private static final int MAX_SPILL_EXEMPT_RESULT_CODE_POINTS = 25_000;
    private static final long MAX_METADATA_BYTES = 64L * 1024L;
    private static final String MANIFEST_ARTIFACT_ID = "manifest";

    private final Path baseDirectory;
    private final int spillThresholdTokens;
    private final int retentionDays;
    private final long maxArtifactBytes;
    private final long maxSessionBytes;
    private final Deque<ToolResultArtifactRef> manifestEntries = new ArrayDeque<>();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

    private Path projectRoot;
    private String projectHash;
    private String sessionId;
    private Path sessionDirectory;
    private long sessionBytes;

    public static ToolResultArtifactStore createDefault(Path projectRoot) {
        String userHome = System.getProperty("user.home", System.getProperty("user.dir", "."));
        String defaultDirectory = Path.of(userHome, ".paicli", "context-artifacts").toString();
        Path directory = Path.of(ConfigValueResolver.resolve(
                "paicli.tool.result.dir",
                "PAICLI_TOOL_RESULT_DIR",
                defaultDirectory));
        int spillTokens = (int) Math.min(Integer.MAX_VALUE, ConfigValueResolver.positiveLong(
                "paicli.tool.result.spill.tokens",
                "PAICLI_TOOL_RESULT_SPILL_TOKENS",
                DEFAULT_SPILL_THRESHOLD_TOKENS));
        int retention = (int) Math.min(Integer.MAX_VALUE, ConfigValueResolver.positiveLong(
                "paicli.tool.result.retention.days",
                "PAICLI_TOOL_RESULT_RETENTION_DAYS",
                DEFAULT_RETENTION_DAYS));
        return new ToolResultArtifactStore(directory, projectRoot, spillTokens, retention);
    }

    public ToolResultArtifactStore(Path baseDirectory, Path projectRoot,
                                   int spillThresholdTokens, int retentionDays) {
        this(baseDirectory, projectRoot, spillThresholdTokens, retentionDays,
                DEFAULT_MAX_ARTIFACT_BYTES, DEFAULT_MAX_SESSION_BYTES);
    }

    ToolResultArtifactStore(Path baseDirectory, Path projectRoot,
                            int spillThresholdTokens, int retentionDays,
                            long maxArtifactBytes, long maxSessionBytes) {
        this.baseDirectory = normalizeAbsolute(baseDirectory == null ? Path.of(".") : baseDirectory);
        this.spillThresholdTokens = Math.max(1, spillThresholdTokens);
        this.retentionDays = Math.max(1, retentionDays);
        this.maxArtifactBytes = Math.max(1, maxArtifactBytes);
        this.maxSessionBytes = Math.max(this.maxArtifactBytes, maxSessionBytes);
        bindProject(projectRoot == null ? Path.of(".") : projectRoot);
        cleanupExpiredSessions();
    }

    /**
     * Projects raw text for model history. The raw value is returned only while it is below the
     * spill threshold (or belongs to the strictly paginated read tool).
     */
    public ToolResultProjection project(String toolName, String toolCallId,
                                        String rawText, boolean spillExempt) {
        lifecycleLock.readLock().lock();
        try {
            synchronized (this) {
                return projectLocked(toolName, toolCallId, rawText, spillExempt);
            }
        } catch (RuntimeException e) {
            log.warn("Tool result projection failed safely: tool={}, error={}",
                    toolName, e.getClass().getSimpleName());
            String safeText = normalizeUnicode(rawText == null ? "" : rawText).text();
            String displayPreview = preview(safeText);
            return failedProjection(toolName, safeText, displayPreview,
                    ToolResultMetadata.from(toolName, safeText), "artifact_projection_failed");
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private ToolResultProjection projectLocked(String toolName, String toolCallId,
                                                String rawText, boolean spillExempt) {
        NormalizedText normalized = normalizeUnicode(rawText == null ? "" : rawText);
        String text = normalized.text();
        ToolResultMetadata metadata = ToolResultMetadata.from(toolName, text);
        int chars = text.codePointCount(0, text.length());
        if (spillExempt) {
            if (chars <= MAX_SPILL_EXEMPT_RESULT_CODE_POINTS) {
                return new ToolResultProjection(text, text, metadata, null, false, false);
            }
            String bounded = preview(text);
            return new ToolResultProjection(
                    "[read_tool_result 分片超过 25000 code points，已拒绝完整回灌；请减小 max_chars]\n"
                            + bounded,
                    bounded,
                    metadata,
                    null,
                    false,
                    false);
        }
        if (MemoryEntry.estimateTokens(text) < spillThresholdTokens) {
            return new ToolResultProjection(text, text, metadata, null, false, false);
        }

        String displayPreview = preview(text);
        long utf8Bytes = utf8Length(text);
        if (CancellationContext.isCancelled()) {
            return failedProjection(toolName, text, displayPreview, metadata,
                    "artifact_write_cancelled");
        }
        if (utf8Bytes > maxArtifactBytes) {
            return failedProjection(toolName, text, displayPreview, metadata,
                    "artifact_too_large");
        }
        if (sessionBytes > maxSessionBytes - utf8Bytes) {
            return failedProjection(toolName, text, displayPreview, metadata,
                    "artifact_session_quota_exceeded");
        }
        if (manifestEntries.size() >= MAX_SESSION_ARTIFACTS) {
            return failedProjection(toolName, text, displayPreview, metadata,
                    "artifact_session_count_exceeded");
        }

        String artifactId = randomId();
        String handle = handle(sessionId, artifactId);
        Instant createdAt = Instant.now();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        ToolResultArtifactRef ref = new ToolResultArtifactRef(
                artifactId,
                handle,
                "sha256:" + projectHash,
                sessionId,
                ToolResultMetadata.truncateCodePoints(valueOrEmpty(toolName), 240),
                ToolResultMetadata.truncateCodePoints(valueOrEmpty(toolCallId), 240),
                createdAt,
                chars,
                utf8Bytes,
                digest,
                true,
                normalized.changed()
        );

        Path textPath = null;
        Path metadataPath = null;
        boolean manifestAdded = false;
        try {
            Path safeSession = ensureSessionDirectoryForWrite();
            textPath = safeSession.resolve(artifactId + ".txt");
            metadataPath = safeSession.resolve(artifactId + ".json");
            applyOwnerOnlyPermissions(baseDirectory, true);
            applyOwnerOnlyPermissions(baseDirectory.resolve(projectHash), true);
            applyOwnerOnlyPermissions(safeSession, true);
            if (CancellationContext.isCancelled()) {
                return failedProjection(toolName, text, displayPreview, metadata,
                        "artifact_write_cancelled");
            }
            writeAtomic(textPath, bytes);
            writeAtomic(metadataPath, metadataJson(ref, metadata));
            manifestEntries.addLast(ref);
            manifestAdded = true;
            writeManifestAtomic();
            sessionBytes += utf8Bytes;
            return new ToolResultProjection(
                    successEnvelope(ref, displayPreview, metadata),
                    displayPreview,
                    metadata,
                    ref,
                    true,
                    false
            );
        } catch (Exception e) {
            if (manifestAdded) {
                manifestEntries.removeLastOccurrence(ref);
            }
            deleteQuietly(textPath);
            deleteQuietly(metadataPath);
            log.warn("Failed to persist tool result artifact: tool={}, chars={}, error={}",
                    toolName, chars, e.getClass().getSimpleName());
            return failedProjection(toolName, text, displayPreview, metadata,
                    "artifact_write_failed");
        }
    }

    /** Reads one code-point-safe page from an artifact or the stable session manifest. */
    public String read(String opaqueHandle, int offset, int requestedMaxChars) {
        lifecycleLock.readLock().lock();
        try {
            synchronized (this) {
                return readLocked(opaqueHandle, offset, requestedMaxChars);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private String readLocked(String opaqueHandle, int offset, int requestedMaxChars) {
        Matcher matcher = HANDLE_PATTERN.matcher(valueOrEmpty(opaqueHandle));
        if (!matcher.matches()) {
            return readError("INVALID_HANDLE", "Expected tool-result://<session-id>/<artifact-id>");
        }
        if (!sessionId.equals(matcher.group(1))) {
            return readError("HANDLE_SCOPE_MISMATCH", "The handle is not visible in this artifact session");
        }
        if (offset < 0) {
            return readError("OFFSET_OUT_OF_RANGE", "offset must be non-negative");
        }
        int maxChars = requestedMaxChars <= 0
                ? DEFAULT_READ_CODE_POINTS
                : Math.min(requestedMaxChars, MAX_READ_CODE_POINTS);
        String artifactId = matcher.group(2);

        try {
            String content;
            String expectedDigest = null;
            Integer expectedChars = null;
            if (MANIFEST_ARTIFACT_ID.equals(artifactId)) {
                content = manifestContent();
            } else {
                Path safeSession = validateActiveSessionDirectory();
                Path textPath = safeSession.resolve(artifactId + ".txt");
                Path metadataPath = safeSession.resolve(artifactId + ".json");
                if (!isSafeRegularFile(textPath, safeSession)
                        || !isSafeRegularFile(metadataPath, safeSession)) {
                    return readError("ARTIFACT_NOT_FOUND", "No artifact exists for this handle");
                }
                if (Files.size(metadataPath) > MAX_METADATA_BYTES) {
                    return readError("ARTIFACT_NOT_FOUND", "Artifact metadata is invalid");
                }
                JsonNode artifactMetadata = mapper.readTree(Files.readString(metadataPath, StandardCharsets.UTF_8));
                ValidatedMetadata validated = validateMetadata(
                        artifactMetadata, artifactId, opaqueHandle, textPath);
                if (validated.createdAt().plus(Duration.ofDays(retentionDays)).isBefore(Instant.now())) {
                    removeExpiredArtifact(artifactId, validated.bytes(), textPath, metadataPath);
                    return readError("ARTIFACT_EXPIRED", "The artifact retention period has elapsed");
                }
                expectedDigest = validated.sha256();
                expectedChars = validated.chars();
                content = Files.readString(textPath, StandardCharsets.UTF_8);
            }

            int total = content.codePointCount(0, content.length());
            if (expectedChars != null && expectedChars != total) {
                return readError("ARTIFACT_DIGEST_MISMATCH", "Stored artifact length no longer matches metadata");
            }
            if (offset > total) {
                return readError("OFFSET_OUT_OF_RANGE", "offset=" + offset + ", total_chars=" + total);
            }
            String actualDigest = sha256(content.getBytes(StandardCharsets.UTF_8));
            if (expectedDigest != null && !expectedDigest.isBlank() && !expectedDigest.equals(actualDigest)) {
                return readError("ARTIFACT_DIGEST_MISMATCH", "Stored artifact content no longer matches metadata");
            }

            int end = (int) Math.min(total, (long) offset + maxChars);
            int startIndex = content.offsetByCodePoints(0, offset);
            int endIndex = content.offsetByCodePoints(0, end);
            String page = content.substring(startIndex, endIndex);
            boolean eof = end >= total;
            touchSession();
            return "[工具结果分片]\n"
                    + "handle: " + opaqueHandle + "\n"
                    + "range: [" + offset + ", " + end + ")\n"
                    + "next_offset: " + end + "\n"
                    + "eof: " + eof + "\n"
                    + "sha256: " + actualDigest + "\n\n"
                    + "--- content ---\n"
                    + page;
        } catch (ArtifactIntegrityException e) {
            return readError("ARTIFACT_DIGEST_MISMATCH", e.getMessage());
        } catch (InvalidArtifactException e) {
            return readError("ARTIFACT_NOT_FOUND", e.getMessage());
        } catch (IOException e) {
            log.warn("Failed to read tool result artifact: handle={}, reason={}", opaqueHandle, e.toString());
            return readError("ARTIFACT_NOT_FOUND", "Artifact storage is unavailable");
        }
    }

    public synchronized String manifestHandle() {
        return handle(sessionId, MANIFEST_ARTIFACT_ID);
    }

    public synchronized int artifactCount() {
        return manifestEntries.size();
    }

    public synchronized long sessionBytes() {
        return sessionBytes;
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    /** Waits for active tool batches, rotates the session, then deletes the old scope asynchronously. */
    public void clearSession() {
        Path oldDirectory;
        String oldProjectHash;
        lifecycleLock.writeLock().lock();
        try {
            synchronized (this) {
                oldDirectory = sessionDirectory;
                oldProjectHash = projectHash;
                newSession();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        CompletableFuture.runAsync(() -> deleteSessionDirectory(oldDirectory, oldProjectHash));
    }

    /** Rebinds an owning registry to a new project scope and starts a fresh session. */
    public void rebindProject(Path newProjectRoot) {
        lifecycleLock.writeLock().lock();
        try {
            synchronized (this) {
                Path normalized = canonicalProject(newProjectRoot == null ? Path.of(".") : newProjectRoot);
                String newHash = sha256(normalized.toString().getBytes(StandardCharsets.UTF_8));
                if (newHash.equals(projectHash)) {
                    this.projectRoot = normalized;
                    return;
                }
                bindProject(normalized);
                cleanupExpiredSessions();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    SessionLease acquireSessionLease() {
        lifecycleLock.readLock().lock();
        return new SessionLease(lifecycleLock.readLock());
    }

    synchronized Path activeSessionDirectory() {
        return sessionDirectory;
    }

    private Path ensureSessionDirectoryForWrite() throws IOException {
        Files.createDirectories(baseDirectory);
        Path baseReal = baseDirectory.toRealPath();
        Path projectDirectory = baseDirectory.resolve(projectHash).normalize();
        ensureOwnedDirectory(projectDirectory, baseReal);
        Path projectReal = projectDirectory.toRealPath();
        Path targetSession = projectDirectory.resolve(sessionId).normalize();
        ensureOwnedDirectory(targetSession, projectReal);
        return validateActiveSessionDirectory();
    }

    private Path validateActiveSessionDirectory() throws IOException {
        if (!Files.isDirectory(baseDirectory)) {
            throw new IOException("Artifact base directory is unavailable");
        }
        Path baseReal = baseDirectory.toRealPath();
        Path projectDirectory = baseDirectory.resolve(projectHash).normalize();
        Path projectReal = validateOwnedDirectory(projectDirectory, baseReal);
        Path targetSession = projectDirectory.resolve(sessionId).normalize();
        return validateOwnedDirectory(targetSession, projectReal);
    }

    private static void ensureOwnedDirectory(Path directory, Path expectedParentReal) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another in-process writer may have created it; the checks below still apply.
            }
        }
        validateOwnedDirectory(directory, expectedParentReal);
        applyOwnerOnlyPermissions(directory, true);
    }

    private static Path validateOwnedDirectory(Path directory, Path expectedParentReal) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Artifact scope directory is not a regular directory");
        }
        Path real = directory.toRealPath();
        if (!expectedParentReal.equals(real.getParent())) {
            throw new IOException("Artifact scope escaped its configured parent");
        }
        return real;
    }

    private static boolean isSafeRegularFile(Path file, Path expectedParentReal) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path real = file.toRealPath();
        return expectedParentReal.equals(real.getParent());
    }

    private ValidatedMetadata validateMetadata(JsonNode metadata, String artifactId,
                                               String opaqueHandle, Path textPath)
            throws IOException {
        if (metadata == null || !metadata.isObject()
                || metadata.path("schema_version").asInt(-1) != 1
                || !artifactId.equals(metadata.path("artifact_id").asText())
                || !opaqueHandle.equals(metadata.path("handle").asText())
                || !("sha256:" + projectHash).equals(metadata.path("project_hash").asText())
                || !sessionId.equals(metadata.path("session_id").asText())
                || !StandardCharsets.UTF_8.name().equalsIgnoreCase(metadata.path("charset").asText())
                || !metadata.path("captured_complete").asBoolean(false)) {
            throw new InvalidArtifactException("Artifact metadata is invalid");
        }
        JsonNode charsNode = metadata.get("chars");
        JsonNode bytesNode = metadata.get("bytes");
        if (charsNode == null || !charsNode.canConvertToInt()
                || bytesNode == null || !bytesNode.canConvertToLong()) {
            throw new InvalidArtifactException("Artifact metadata length is invalid");
        }
        int chars = charsNode.intValue();
        long bytes = bytesNode.longValue();
        String digest = metadata.path("sha256").asText("");
        Instant createdAt = parseInstant(metadata.path("created_at").asText(""));
        if (chars < 0 || bytes < 0 || bytes > maxArtifactBytes
                || !digest.matches("[0-9a-f]{64}")
                || createdAt == null
                || createdAt.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new InvalidArtifactException("Artifact metadata is invalid");
        }
        long actualBytes = Files.size(textPath);
        if (actualBytes > maxArtifactBytes || actualBytes != bytes) {
            throw new ArtifactIntegrityException("Artifact body length does not match metadata");
        }
        return new ValidatedMetadata(createdAt, bytes, chars, digest);
    }

    private void bindProject(Path root) {
        this.projectRoot = canonicalProject(root);
        this.projectHash = sha256(this.projectRoot.toString().getBytes(StandardCharsets.UTF_8));
        newSession();
    }

    private void newSession() {
        this.sessionId = randomId();
        this.sessionDirectory = baseDirectory.resolve(projectHash).resolve(sessionId).normalize();
        this.sessionBytes = 0L;
        this.manifestEntries.clear();
    }

    private ToolResultProjection failedProjection(String toolName, String text, String displayPreview,
                                                  ToolResultMetadata metadata, String reason) {
        int chars = text.codePointCount(0, text.length());
        String envelope = "[工具大结果未完整保留: " + reason + "]\n"
                + "handle: unavailable\n"
                + "original_chars: " + chars + "\n"
                + "tool: " + ToolResultMetadata.truncateCodePoints(valueOrEmpty(toolName), 240) + "\n"
                + "captured_complete: false\n\n"
                + displayPreview;
        return new ToolResultProjection(envelope, displayPreview, metadata, null, true, true);
    }

    private String successEnvelope(ToolResultArtifactRef ref, String displayPreview,
                                   ToolResultMetadata metadata) {
        int previewChars = displayPreview.codePointCount(0, displayPreview.length());
        return "[工具结果已落盘]\n"
                + "handle: " + ref.handle() + "\n"
                + "original_chars: " + ref.chars() + "\n"
                + "读取更多: read_tool_result {\"handle\":\"" + ref.handle()
                + "\",\"offset\":0,\"max_chars\":" + DEFAULT_READ_CODE_POINTS + "}\n"
                + "tool: " + ref.toolName() + "\n"
                + "artifact_manifest: " + manifestHandle() + "\n"
                + "sha256: " + ref.sha256() + "\n"
                + "captured_complete: " + ref.capturedComplete() + "\n"
                + "upstream_partial: " + metadata.partial() + "\n"
                + "upstream_truncated: " + metadata.producerTruncated() + "\n"
                + "preview_chars: " + previewChars + "\n"
                + "preview_bounds: head<=4000, tail<=1000 code_points\n\n"
                + displayPreview;
    }

    private static String preview(String text) {
        int total = text.codePointCount(0, text.length());
        int combined = PREVIEW_HEAD_CODE_POINTS + PREVIEW_TAIL_CODE_POINTS;
        if (total <= combined) {
            return text;
        }
        String head = sliceCodePoints(text, 0, PREVIEW_HEAD_CODE_POINTS);
        String tail = sliceCodePoints(text, total - PREVIEW_TAIL_CODE_POINTS, total);
        int omitted = total - combined;
        return "--- preview head ---\n" + head
                + "\n--- omitted: " + omitted + " chars ---\n"
                + "--- preview tail ---\n" + tail;
    }

    private byte[] metadataJson(ToolResultArtifactRef ref, ToolResultMetadata metadata) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("schema_version", 1);
        node.put("artifact_id", ref.artifactId());
        node.put("handle", ref.handle());
        node.put("project_hash", ref.projectHash());
        node.put("session_id", ref.sessionId());
        node.put("tool_name", ref.toolName());
        node.put("tool_call_id", ref.toolCallId());
        node.put("created_at", ref.createdAt().toString());
        node.put("charset", StandardCharsets.UTF_8.name());
        node.put("chars", ref.chars());
        node.put("bytes", ref.bytes());
        node.put("sha256", ref.sha256());
        node.put("captured_complete", ref.capturedComplete());
        node.put("unicode_normalized", ref.unicodeNormalized());
        node.put("upstream_partial", metadata.partial());
        node.put("upstream_truncated", metadata.producerTruncated());
        node.put("source_label", metadata.sourceLabel());
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    private void writeManifestAtomic() throws IOException {
        writeAtomic(sessionDirectory.resolve("manifest.jsonl"),
                manifestContent().getBytes(StandardCharsets.UTF_8));
    }

    private String manifestContent() throws IOException {
        ObjectNode header = mapper.createObjectNode();
        header.put("schema_version", 1);
        header.put("session_id", sessionId);
        header.put("manifest_handle", manifestHandle());
        StringBuilder manifest = new StringBuilder(mapper.writeValueAsString(header)).append('\n');
        ObjectNode state = mapper.createObjectNode();
        state.put("artifact_count", manifestEntries.size());
        long indexedBytes = manifestEntries.stream().mapToLong(ToolResultArtifactRef::bytes).sum();
        state.put("artifact_body_bytes", indexedBytes);
        manifest.append(mapper.writeValueAsString(state)).append('\n');
        for (ToolResultArtifactRef ref : manifestEntries) {
            ObjectNode item = mapper.createObjectNode();
            item.put("handle", ref.handle());
            item.put("tool_name", ref.toolName());
            item.put("chars", ref.chars());
            item.put("bytes", ref.bytes());
            item.put("sha256", ref.sha256());
            item.put("created_at", ref.createdAt().toString());
            item.put("captured_complete", ref.capturedComplete());
            item.put("status", "available");
            manifest.append(mapper.writeValueAsString(item)).append('\n');
        }
        return manifest.toString();
    }

    private void writeAtomic(Path target, byte[] content) throws IOException {
        Path safeSession = ensureSessionDirectoryForWrite();
        Path requestedParent = target.getParent().toRealPath();
        if (!safeSession.equals(requestedParent) || Files.isSymbolicLink(target)) {
            throw new IOException("Artifact write target escaped the active session");
        }
        target = safeSession.resolve(target.getFileName().toString());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + randomId());
        try {
            Files.write(temp, content);
            applyOwnerOnlyPermissions(temp, false);
            if (CancellationContext.isCancelled()) {
                throw new IOException("artifact write cancelled");
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                if (!Files.exists(temp)) {
                    throw atomicFailure;
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(atomicFailure);
                    throw fallbackFailure;
                }
            }
            applyOwnerOnlyPermissions(target, false);
        } finally {
            deleteQuietly(temp);
        }
    }

    private void cleanupExpiredSessions() {
        Path projectDirectory = baseDirectory.resolve(projectHash).normalize();
        if (!Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(projectDirectory)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(projectDirectory)) {
            for (Path candidate : sessions) {
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(candidate)
                        || candidate.equals(sessionDirectory)) {
                    continue;
                }
                try {
                    Instant modified = Files.getLastModifiedTime(candidate).toInstant();
                    if (modified.isBefore(cutoff)) {
                        deleteSessionDirectory(candidate, projectHash);
                    }
                } catch (IOException e) {
                    log.debug("Failed to inspect expired artifact session: project={}, session={}",
                            projectHash, candidate.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan artifact retention directory: project={}", projectHash, e);
        }
    }

    private void touchSession() {
        try {
            if (Files.isDirectory(sessionDirectory)) {
                Files.setLastModifiedTime(sessionDirectory,
                        java.nio.file.attribute.FileTime.from(Instant.now()));
            }
        } catch (IOException ignored) {
            // Retention freshness is best effort and must not fail a valid read.
        }
    }

    private void removeExpiredArtifact(String artifactId, long storedBytes,
                                       Path textPath, Path metadataPath) {
        manifestEntries.removeIf(ref -> artifactId.equals(ref.artifactId()));
        sessionBytes = Math.max(0L, sessionBytes - Math.max(0L, storedBytes));
        deleteQuietly(textPath);
        deleteQuietly(metadataPath);
        try {
            if (Files.isDirectory(sessionDirectory)) {
                writeManifestAtomic();
            }
        } catch (IOException e) {
            log.debug("Failed to refresh manifest after expiring artifact {}", artifactId, e);
        }
    }

    private void deleteSessionDirectory(Path target, String scopeProjectHash) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        Path expectedParent = baseDirectory.resolve(scopeProjectHash).normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(expectedParent.toAbsolutePath().normalize())
                || normalizedTarget.equals(expectedParent.toAbsolutePath().normalize())) {
            log.warn("Refusing to delete artifact path outside its project scope: project={}, session={}",
                    scopeProjectHash, target.getFileName());
            return;
        }
        try {
            if (Files.isSymbolicLink(expectedParent)
                    || !Files.isDirectory(expectedParent, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path baseReal = baseDirectory.toRealPath();
            Path projectReal = validateOwnedDirectory(expectedParent, baseReal);
            if (Files.isSymbolicLink(normalizedTarget)) {
                Files.deleteIfExists(normalizedTarget);
                return;
            }
            Path targetReal = validateOwnedDirectory(normalizedTarget, projectReal);
            try (var paths = Files.walk(targetReal)) {
                List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
                for (Path path : ordered) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            log.debug("Best-effort artifact session cleanup failed: project={}, session={}",
                    scopeProjectHash, target.getFileName(), e);
        }
    }

    private static NormalizedText normalizeUnicode(String text) {
        boolean needsNormalization = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                } else {
                    needsNormalization = true;
                    break;
                }
            } else if (Character.isLowSurrogate(current)) {
                needsNormalization = true;
                break;
            }
        }
        if (!needsNormalization) {
            return new NormalizedText(text, false);
        }
        StringBuilder normalized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    normalized.append(current).append(text.charAt(++i));
                } else {
                    normalized.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(current)) {
                normalized.append('\uFFFD');
            } else {
                normalized.append(current);
            }
        }
        return new NormalizedText(normalized.toString(), true);
    }

    static String sliceCodePoints(String value, int startInclusive, int endExclusive) {
        int start = value.offsetByCodePoints(0, startInclusive);
        int end = value.offsetByCodePoints(0, endExclusive);
        return value.substring(start, end);
    }

    private static String readError(String code, String detail) {
        return code + "\n" + detail;
    }

    private static String handle(String sessionId, String artifactId) {
        return "tool-result://" + sessionId + "/" + artifactId;
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Path canonicalProject(Path path) {
        try {
            return path.toRealPath().normalize();
        } catch (IOException ignored) {
            return normalizeAbsolute(path);
        }
    }

    private static Path normalizeAbsolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static long utf8Length(String value) {
        long bytes = 0L;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint <= 0x7F) {
                bytes++;
            } else if (codePoint <= 0x7FF) {
                bytes += 2;
            } else if (codePoint <= 0xFFFF) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static void applyOwnerOnlyPermissions(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs and non-POSIX filesystems use their platform defaults.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record NormalizedText(String text, boolean changed) {
    }

    private record ValidatedMetadata(Instant createdAt, long bytes, int chars, String sha256) {
    }

    private static final class InvalidArtifactException extends IOException {
        private InvalidArtifactException(String message) {
            super(message);
        }
    }

    private static final class ArtifactIntegrityException extends IOException {
        private ArtifactIntegrityException(String message) {
            super(message);
        }
    }

    static final class SessionLease implements AutoCloseable {
        private final Lock lock;
        private boolean closed;

        private SessionLease(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
