package com.paicli.agent;

import com.paicli.context.RequestContextBudget;
import com.paicli.context.RequestContextEstimator;
import com.paicli.llm.LlmClient;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextVisibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void contextCommandAndBottomStatusUseTheUnifiedEstimatedInput() {
        VisibleClient client = new VisibleClient();
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("artifacts"), tempDir, 100_000, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        try {
            Agent agent = new Agent(client, registry);
            assertEquals("ok", agent.run("hello"));

            RequestContextBudget expected = new RequestContextEstimator(
                    client, agent.getMemoryManager().getContextProfile())
                    .estimate(agent.getConversationHistory(), registry.getToolDefinitions());
            StatusInfo status = agent.currentStatus("idle");
            String report = agent.getContextStatus();

            assertEquals(expected.estimatedInputTokens(), status.totalTokens());
            assertTrue(report.contains("Context Usage"));
            assertTrue(report.contains("Source history"));
            assertTrue(report.contains("Model view messages"));
            assertTrue(report.contains("images (included)"));
            assertTrue(report.contains("replayed reasoning"));
            assertTrue(report.contains("Tools wire schema"));
            assertTrue(report.contains("Framing / envelope"));
            assertTrue(report.contains("Estimated input"));
            assertTrue(report.contains("Response reserve"));
            assertTrue(report.contains("Safety margin"));
            assertTrue(report.contains("Hard input ceiling"));
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    private static final class VisibleClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", "ok", null, 10, 2);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "ok", null, 10, 2);
        }

        @Override
        public String getModelName() {
            return "visible-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }
    }
}
