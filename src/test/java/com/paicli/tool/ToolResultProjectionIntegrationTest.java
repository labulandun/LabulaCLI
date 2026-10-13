package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.protocol.McpToolDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultProjectionIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void executeToolsProjectsLegacyLargeResultButKeepsPublicExecuteToolRaw() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("legacy-artifacts"), tempDir, 1, 7);
        String raw = "legacy-raw-" + "x".repeat(100_000);
        ToolRegistry registry = new ToolRegistry(store) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("legacy_large".equals(name)) {
                    return raw;
                }
                return super.executeTool(name, argumentsJson);
            }
        };
        registry.setProjectPath(tempDir.toString());

        assertEquals(raw, registry.executeTool("legacy_large", "{}"));

        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_legacy", "legacy_large", "{}"))).get(0);

        assertTrue(result.spilled());
        assertTrue(result.result().contains("[工具结果已落盘]"));
        assertFalse(result.result().contains(raw));
        assertTrue(result.displayPreview().length() < raw.length());
        assertEquals("legacy-raw-", result.resultMetadata().firstLine().substring(0, 11));
    }

    @Test
    void readToolIsSpillExemptAndReadOnlyExploreViewSharesScope() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("shared-artifacts"), tempDir, 1, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        registry.registerMcpToolOutput(descriptor("large"), args -> new ToolOutput(
                "MCP-PAYLOAD-" + "😀中".repeat(30_000),
                List.of(LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png"))));

        ToolRegistry.ToolExecutionResult large = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_mcp", "mcp__demo__large", "{}"))).get(0);

        assertTrue(large.spilled());
        assertTrue(large.hasImageParts());
        assertEquals("image/png", large.imageParts().get(0).mimeType());

        String args = MAPPER.createObjectNode()
                .put("handle", large.artifactRef().handle())
                .put("offset", 0)
                .put("max_chars", 100)
                .toString();
        ToolRegistry.ToolExecutionResult page = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_read", "read_tool_result", args))).get(0);
        assertFalse(page.spilled(), "严格分页的 read_tool_result 不得递归产生新 artifact");
        assertTrue(page.result().contains("MCP-PAYLOAD-"));

        ToolRegistry readOnly = registry.readOnlyExploreView();
        assertTrue(readOnly.hasTool("read_tool_result"));
        assertTrue(readOnly.executeTool("read_tool_result", args).contains("MCP-PAYLOAD-"));
        assertEquals(registry.getToolResultManifestHandle(), readOnly.getToolResultManifestHandle());
    }

    @Test
    void nestedStepSearchIsProjectedExactlyOnceAtTheOuterBoundary() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("step-artifacts"), tempDir, 1, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepDescriptor("web_search"), args ->
                "1. Step result\n" + "body".repeat(20_000));

        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation(
                        "call_step", "web_search", "{\"query\":\"phase 90\",\"top_k\":5}"))).get(0);

        assertTrue(result.spilled());
        String firstPage = registry.executeTool("read_tool_result", MAPPER.createObjectNode()
                .put("handle", result.artifactRef().handle())
                .put("offset", 0)
                .put("max_chars", 1_000)
                .toString());
        assertTrue(firstPage.contains("[StepSearch]"));
        assertFalse(firstPage.contains("[工具结果已落盘]"), "nested StepSearch 不应先生成一层 envelope");
    }

    private static McpToolDescriptor descriptor(String name) throws Exception {
        return new McpToolDescriptor(
                "demo",
                name,
                "mcp__demo__" + name,
                "demo " + name,
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}"));
    }

    private static McpToolDescriptor stepDescriptor(String name) throws Exception {
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}"));
    }
}
