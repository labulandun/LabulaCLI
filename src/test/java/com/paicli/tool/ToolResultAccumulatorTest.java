package com.paicli.tool;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultAccumulatorTest {

    @Test
    void keepsPlanFallbackBoundedAndPreservesArtifactIndex() {
        String manifest = "tool-result://session/manifest";
        ToolResultAccumulator accumulator = new ToolResultAccumulator(manifest, 2_000);
        for (int i = 0; i < 20; i++) {
            ToolResultArtifactRef ref = new ToolResultArtifactRef(
                    "artifact" + i,
                    "tool-result://session/artifact" + i,
                    "sha256:project",
                    "session",
                    "large_tool",
                    "call_" + i,
                    Instant.now(),
                    300_000,
                    300_000,
                    "digest" + i,
                    true,
                    false);
            accumulator.add(new ToolExecutionResult(
                    "call_" + i,
                    "large_tool",
                    "{}",
                    "[工具结果已落盘]\nhandle: " + ref.handle(),
                    1,
                    false,
                    List.of(),
                    "raw-preview-" + "x".repeat(5_000),
                    ToolResultMetadata.from("large_tool", "summary-" + i),
                    ref,
                    false));
        }

        String result = accumulator.build();

        assertTrue(result.codePointCount(0, result.length()) <= 2_000);
        assertTrue(result.contains(manifest));
        assertTrue(result.contains("partial: true"));
        assertTrue(result.contains("tool-result://session/artifact0"));
        assertFalse(result.contains("x".repeat(1_000)));
    }

    @Test
    void omitsManifestUntilAnArtifactWasActuallyCreated() {
        ToolResultAccumulator accumulator = new ToolResultAccumulator(
                "tool-result://session/manifest",
                2_000);
        accumulator.add(new ToolExecutionResult(
                "call_1", "read_file", "{}", "small result", 1, false, List.of()));

        String result = accumulator.build();

        assertFalse(result.contains("artifact_manifest:"));
        assertEquals(0, result.indexOf("[工具结果有界汇总]"));
    }

    @Test
    void detectsWhitespaceInStructuredPartialMetadata() {
        ToolResultMetadata metadata = ToolResultMetadata.from(
                "mcp__demo__search", "{ \"partial\" : true, \"truncated\" : true }");

        assertTrue(metadata.partial());
        assertTrue(metadata.producerTruncated());
    }
}
