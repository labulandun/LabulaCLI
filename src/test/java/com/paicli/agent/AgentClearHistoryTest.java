package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentClearHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void clearHistoryRebuildsSystemPromptAndDropsPendingSkillContext() {
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            RecordingClient llmClient = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "ok", null, 50_000, 1_000)
            ));
            Agent agent = new Agent(llmClient);
            SkillContextBuffer skillContextBuffer = new SkillContextBuffer();
            agent.setSkillContextBuffer(skillContextBuffer);
            agent.getMemoryManager().storeFact("CLEAR_MARKER should only appear when retrieved", "project");

            agent.run("CLEAR_MARKER");

            assertTrue(llmClient.firstSystemPrompt().contains("CLEAR_MARKER"),
                    "sanity check: the first turn should inject query-specific long-term memory");
            long beforeClearTokens = agent.currentStatus("idle").totalTokens();

            skillContextBuffer.push("demo", "pending skill body");
            agent.clearHistory();

            List<LlmClient.Message> history = agent.getConversationHistory();
            assertEquals(1, history.size());
            assertFalse(history.get(0).content().contains("CLEAR_MARKER"),
                    "/clear must not preserve the previous query's retrieved memory in system prompt");
            assertFalse(history.get(0).content().contains("## 相关长期记忆"));
            assertEquals("", skillContextBuffer.drain(), "/clear should drop pending skill injection");
            assertTrue(agent.currentStatus("idle").totalTokens() < beforeClearTokens,
                    "status ctx should reflect the cleared conversation instead of the previous LLM usage");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void clearHistoryRotatesToolResultArtifactSession() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("artifacts"), tempDir, 1, 7);
        ToolRegistry registry = new ToolRegistry(store) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("large".equals(name)) {
                    return "artifact-payload-".repeat(5_000);
                }
                return super.executeTool(name, argumentsJson);
            }
        };
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new RecordingClient(List.of()), registry);
        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_clear", "large", "{}"))).get(0);
        String oldHandle = result.artifactRef().handle();
        String oldManifest = registry.getToolResultManifestHandle();
        assertTrue(agent.getContextStatus().contains("Tool artifacts: 1 files"));

        agent.clearHistory();

        assertFalse(oldManifest.equals(registry.getToolResultManifestHandle()));
        assertTrue(agent.getContextStatus().contains("Tool artifacts: 0 files"));
        assertTrue(registry.executeTool("read_tool_result",
                        "{\"handle\":\"" + oldHandle + "\",\"offset\":0,\"max_chars\":10}")
                .startsWith("HANDLE_SCOPE_MISMATCH"));
    }

    @Test
    void projectSwitchClearsMessagesThatReferenceTheOldArtifactScope() throws Exception {
        Path firstProject = Files.createDirectory(tempDir.resolve("project-one"));
        Path secondProject = Files.createDirectory(tempDir.resolve("project-two"));
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("project-artifacts"), firstProject, 1, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(firstProject.toString());
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "ok", null, 10, 2)));
        Agent agent = new Agent(client, registry);
        agent.run("remember this turn");
        String oldManifest = registry.getToolResultManifestHandle();
        assertTrue(agent.getConversationHistory().size() > 1);

        assertTrue(agent.switchProjectPath(secondProject.toString()));

        assertEquals(1, agent.getConversationHistory().size());
        assertFalse(oldManifest.equals(registry.getToolResultManifestHandle()));
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> capturedMessages = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            capturedMessages.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }

        private String firstSystemPrompt() {
            return capturedMessages.get(0).get(0).content();
        }
    }
}
