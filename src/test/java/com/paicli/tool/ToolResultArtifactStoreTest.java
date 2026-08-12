package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolResultArtifactStoreTest {
    private static final Pattern NEXT_OFFSET = Pattern.compile("next_offset: (\\d+)");

    @TempDir
    Path tempDir;

    @Test
    void spillsLargeUtf8ResultAndReconstructsItByCodePointPages() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("artifacts"), tempDir, 1, 7);
        String raw = "prefix-" + "A😀中".repeat(40_000) + "-tail";

        ToolResultProjection projection = store.project("mcp__demo__large", "call_1", raw, false);

        assertTrue(projection.spilled());
        assertFalse(projection.artifactWriteFailed());
        assertTrue(projection.contextText().codePointCount(0, projection.contextText().length()) < 6_000);
        assertTrue(projection.contextText().substring(0, Math.min(500, projection.contextText().length()))
                .contains(projection.artifactRef().handle()), "handle 必须落在 MemoryManager 的前 500 字符内");
        assertEquals(raw.codePointCount(0, raw.length()), projection.artifactRef().chars());
        assertEquals(sha256(raw), projection.artifactRef().sha256());

        Path metadataPath = store.activeSessionDirectory()
                .resolve(projection.artifactRef().artifactId() + ".json");
        String metadata = Files.readString(metadataPath, StandardCharsets.UTF_8);
        assertTrue(metadata.contains("\"schema_version\" : 1"));
        assertTrue(metadata.contains("\"charset\" : \"UTF-8\""));
        assertFalse(metadata.contains("call arguments must never be persisted"));

        String reconstructed = readAll(store, projection.artifactRef().handle(), 7_777);
        assertEquals(raw, reconstructed);
        assertEquals(projection.artifactRef().sha256(), sha256(reconstructed));

        String manifest = store.read(store.manifestHandle(), 0, 24_000);
        assertTrue(manifest.contains(projection.artifactRef().handle()));
        assertTrue(manifest.contains(projection.artifactRef().sha256()));

        String cappedPage = store.read(projection.artifactRef().handle(), 0, 100_000);
        assertEquals(ToolResultArtifactStore.MAX_READ_CODE_POINTS,
                extractContent(cappedPage).codePointCount(0, extractContent(cappedPage).length()));
    }

    @Test
    void normalizesLoneSurrogatesBeforeDigestPreviewAndPersistence() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("unicode"), tempDir, 1, 7);
        String malformed = "left-\uD83D-right";

        ToolResultProjection projection = store.project("demo", "call_unicode", malformed, false);

        assertNotNull(projection.artifactRef());
        assertTrue(projection.artifactRef().unicodeNormalized());
        assertEquals("left-�-right", readAll(store, projection.artifactRef().handle(), 3));
    }

    @Test
    void readIsSpillExemptAndClearInvalidatesOldSessionHandles() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("scope"), tempDir, 1, 7);
        ToolResultProjection projection = store.project("demo", "call_scope", "payload-".repeat(1_000), false);
        String oldHandle = projection.artifactRef().handle();
        String oldManifest = store.manifestHandle();

        ToolResultProjection readProjection = store.project(
                "read_tool_result", "call_read", store.read(oldHandle, 0, 1_000), true);
        assertFalse(readProjection.spilled());
        assertFalse(readProjection.spillAttempted());

        store.clearSession();

        assertTrue(store.read(oldHandle, 0, 100).startsWith("HANDLE_SCOPE_MISMATCH"));
        assertFalse(store.manifestHandle().equals(oldManifest));
    }

    @Test
    void writeAndQuotaFailuresReturnBoundedNonRecoverableEnvelope() throws Exception {
        Path blockedBase = Files.writeString(tempDir.resolve("not-a-directory"), "blocked");
        ToolResultArtifactStore blocked = new ToolResultArtifactStore(blockedBase, tempDir, 1, 7);
        String large = "SECRET-RAW-CONTENT-".repeat(10_000);

        ToolResultProjection writeFailure = blocked.project("demo", "call_failed", large, false);

        assertTrue(writeFailure.artifactWriteFailed());
        assertTrue(writeFailure.contextText().contains("artifact_write_failed"));
        assertTrue(writeFailure.contextText().codePointCount(0, writeFailure.contextText().length()) < 6_000);
        assertFalse(writeFailure.contextText().contains(large));

        ToolResultArtifactStore quotaStore = new ToolResultArtifactStore(
                tempDir.resolve("quota"), tempDir, 1, 7, 128, 256);
        String quotaRaw = "x".repeat(10_000);
        ToolResultProjection quotaFailure = quotaStore.project(
                "demo", "call_quota", quotaRaw, false);
        assertTrue(quotaFailure.artifactWriteFailed());
        assertTrue(quotaFailure.contextText().contains("artifact_too_large"));
        assertFalse(quotaFailure.contextText().contains(quotaRaw));

        ToolResultArtifactStore sessionQuotaStore = new ToolResultArtifactStore(
                tempDir.resolve("session-quota"), tempDir, 1, 7, 1_000, 1_500);
        assertTrue(sessionQuotaStore.project("demo", "first", "a".repeat(900), false).spilled());
        ToolResultProjection sessionQuotaFailure = sessionQuotaStore.project(
                "demo", "second", "b".repeat(900), false);
        assertTrue(sessionQuotaFailure.contextText().contains("artifact_session_quota_exceeded"));
    }

    @Test
    void validatesHandleAndCodePointOffset() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("validation"), tempDir, 1, 7);
        ToolResultProjection projection = store.project("demo", "call_validation", "😀abc".repeat(100), false);

        assertTrue(store.read("file:///tmp/result", 0, 10).startsWith("INVALID_HANDLE"));
        assertTrue(store.read(projection.artifactRef().handle(), -1, 10).startsWith("OFFSET_OUT_OF_RANGE"));
        assertTrue(store.read(projection.artifactRef().handle(), 10_000, 10).startsWith("OFFSET_OUT_OF_RANGE"));

        String page = store.read(projection.artifactRef().handle(), 1, 1);
        assertEquals("a", extractContent(page));
    }

    @Test
    void detectsPersistedArtifactDigestMismatch() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("digest"), tempDir, 1, 7);
        ToolResultProjection projection = store.project(
                "demo", "call_digest", "original".repeat(1_000), false);
        Files.writeString(
                store.activeSessionDirectory().resolve(projection.artifactRef().artifactId() + ".txt"),
                "tampered",
                StandardCharsets.UTF_8);

        assertTrue(store.read(projection.artifactRef().handle(), 0, 100)
                .startsWith("ARTIFACT_DIGEST_MISMATCH"));
    }

    @Test
    void cleansExpiredInactiveSessionsOnStartup() throws Exception {
        Path base = tempDir.resolve("retention");
        ToolResultArtifactStore first = new ToolResultArtifactStore(base, tempDir, 1, 7);
        ToolResultProjection projection = first.project("demo", "call_old", "old".repeat(1_000), false);
        Path oldSession = first.activeSessionDirectory();
        assertTrue(Files.exists(oldSession.resolve(projection.artifactRef().artifactId() + ".txt")));
        Files.setLastModifiedTime(oldSession, FileTime.from(Instant.now().minusSeconds(8L * 24L * 60L * 60L)));

        new ToolResultArtifactStore(base, tempDir, 1, 7);

        assertFalse(Files.exists(oldSession));
    }

    @Test
    void expiredArtifactIsRemovedFromManifestAndSessionAccounting() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("artifact-expiry"), tempDir, 1, 1);
        ToolResultProjection projection = store.project(
                "demo", "call_expired", "expired".repeat(1_000), false);
        Path metadataPath = store.activeSessionDirectory()
                .resolve(projection.artifactRef().artifactId() + ".json");
        String metadata = Files.readString(metadataPath, StandardCharsets.UTF_8)
                .replace(projection.artifactRef().createdAt().toString(),
                        Instant.now().minusSeconds(2L * 24L * 60L * 60L).toString());
        Files.writeString(metadataPath, metadata, StandardCharsets.UTF_8);

        assertTrue(store.read(projection.artifactRef().handle(), 0, 100)
                .startsWith("ARTIFACT_EXPIRED"));
        assertEquals(0, store.artifactCount());
        assertEquals(0, store.sessionBytes());
        assertFalse(store.read(store.manifestHandle(), 0, 10_000)
                .contains(projection.artifactRef().handle()));
    }

    @Test
    void rejectsSymlinkedArtifactBodyInsteadOfReadingOutsideSession() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("symlink-read"), tempDir, 1, 7);
        String raw = "scope-secret".repeat(1_000);
        ToolResultProjection projection = store.project("demo", "call_link", raw, false);
        Path body = store.activeSessionDirectory()
                .resolve(projection.artifactRef().artifactId() + ".txt");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), raw, StandardCharsets.UTF_8);
        Files.delete(body);
        try {
            Files.createSymbolicLink(body, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "当前文件系统不允许创建符号链接");
        }

        assertTrue(store.read(projection.artifactRef().handle(), 0, 100)
                .startsWith("ARTIFACT_NOT_FOUND"));
    }

    @Test
    void refusesToWriteThroughSymlinkedSessionDirectory() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("symlink-write"), tempDir, 1, 7);
        Path session = store.activeSessionDirectory();
        Files.createDirectories(session.getParent());
        Path outside = Files.createDirectory(tempDir.resolve("outside-dir"));
        try {
            Files.createSymbolicLink(session, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "当前文件系统不允许创建符号链接");
        }

        ToolResultProjection projection = store.project(
                "demo", "call_link_write", "do-not-write".repeat(1_000), false);

        assertTrue(projection.artifactWriteFailed());
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void malformedMetadataFailsClosedBeforeReadingBody() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("invalid-metadata"), tempDir, 1, 7);
        ToolResultProjection projection = store.project(
                "demo", "call_metadata", "body".repeat(1_000), false);
        Path metadataPath = store.activeSessionDirectory()
                .resolve(projection.artifactRef().artifactId() + ".json");
        String metadata = Files.readString(metadataPath, StandardCharsets.UTF_8)
                .replace(projection.artifactRef().sha256(), "not-a-digest");
        Files.writeString(metadataPath, metadata, StandardCharsets.UTF_8);

        assertTrue(store.read(projection.artifactRef().handle(), 0, 100)
                .startsWith("ARTIFACT_NOT_FOUND"));
    }

    @Test
    void clearWaitsForActiveArtifactSessionLease() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("lease"), tempDir, 1, 7);
        String oldSession = store.sessionId();
        ToolResultArtifactStore.SessionLease lease = store.acquireSessionLease();
        CompletableFuture<Void> clearing = CompletableFuture.runAsync(store::clearSession);
        Thread.sleep(50);
        assertFalse(clearing.isDone());

        lease.close();
        clearing.get(5, TimeUnit.SECONDS);

        assertFalse(oldSession.equals(store.sessionId()));
    }

    private static String readAll(ToolResultArtifactStore store, String handle, int pageSize) {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (true) {
            String page = store.read(handle, offset, pageSize);
            result.append(extractContent(page));
            if (page.contains("eof: true")) {
                return result.toString();
            }
            Matcher matcher = NEXT_OFFSET.matcher(page);
            assertTrue(matcher.find(), page);
            offset = Integer.parseInt(matcher.group(1));
        }
    }

    private static String extractContent(String page) {
        String marker = "--- content ---\n";
        int index = page.indexOf(marker);
        assertTrue(index >= 0, page);
        return page.substring(index + marker.length());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
