package com.paicli.agent;

import com.paicli.context.ContextProfile;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryProjectionAgentIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void reactSendsModelViewWhilePublicHistoryRemainsSourceAndClearResetsProjection() {
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        try {
            ProjectionAwareClient client = new ProjectionAwareClient(false);
            Agent agent = new Agent(client, registry("react-artifacts"));

            for (int i = 0; i < 4; i++) {
                assertEquals("business-answer", agent.run("react-turn-" + i + " " + "x".repeat(8_000)));
            }

            List<LlmClient.Message> source = agent.getConversationHistory();
            List<LlmClient.Message> modelView = agent.getLastModelConversationView();
            assertTrue(agent.getHistoryProjectionState().hasCheckpoint());
            assertTrue(source.stream().anyMatch(message -> message.content() != null
                    && message.content().startsWith("react-turn-0")));
            assertFalse(modelView.stream().anyMatch(message -> message.content() != null
                    && message.content().startsWith("react-turn-0")));
            assertTrue(modelView.stream().anyMatch(message -> message.content() != null
                    && message.content().contains("[已压缩的历史对话摘要]")));
            assertEquals(modelView, client.lastBusinessMessages());
            assertThrows(UnsupportedOperationException.class,
                    () -> modelView.add(LlmClient.Message.user("mutate")));

            ProjectionAwareClient replacementClient = new ProjectionAwareClient(true);
            agent.setLlmClient(replacementClient);
            assertTrue(agent.getLastModelConversationView().isEmpty());
            assertFalse(agent.getHistoryProjectionState().hasCheckpoint());
            assertTrue(agent.getConversationHistory().stream().anyMatch(message -> message.content() != null
                    && message.content().startsWith("react-turn-0")),
                    "model switches retain source history while invalidating the checkpoint");

            assertEquals("business-answer", agent.run("after-model-switch"));
            assertFalse(agent.getLastModelConversationView().isEmpty());

            agent.clearHistory();

            assertEquals(1, agent.getConversationHistory().size());
            assertTrue(agent.getLastModelConversationView().isEmpty());
            assertFalse(agent.getHistoryProjectionState().hasCheckpoint());
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void workerSubAgentSeparatesSourceAndViewAndClearDoesNotRotateSharedArtifacts() {
        ProjectionAwareClient client = new ProjectionAwareClient(true);
        ToolRegistry registry = registry("worker-artifacts");
        registry.setContextProfile(ContextProfile.custom(8_000, 4_000));
        SubAgent worker = new SubAgent("worker", AgentRole.WORKER, client, registry);

        String manifestBefore = registry.getToolResultManifestHandle();
        for (int i = 0; i < 4; i++) {
            AgentMessage result = worker.execute(AgentMessage.task(
                    "orchestrator", "worker-turn-" + i + " " + "x".repeat(8_000)));
            assertEquals(AgentMessage.Type.RESULT, result.type());
        }

        assertTrue(worker.getHistoryProjectionState().hasCheckpoint());
        assertTrue(worker.getConversationHistory().stream().anyMatch(message -> message.content() != null
                && message.content().startsWith("worker-turn-0")));
        assertFalse(worker.getLastModelConversationView().stream().anyMatch(message -> message.content() != null
                && message.content().startsWith("worker-turn-0")));
        assertEquals(worker.getLastModelConversationView(), client.lastBusinessMessages());

        worker.clearHistory();

        assertEquals(1, worker.getConversationHistory().size());
        assertTrue(worker.getLastModelConversationView().isEmpty());
        assertFalse(worker.getHistoryProjectionState().hasCheckpoint());
        assertEquals(manifestBefore, registry.getToolResultManifestHandle(),
                "SubAgent.clearHistory must not rotate the shared artifact session");
    }

    private ToolRegistry registry(String directory) {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve(directory), tempDir, 100_000, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        return registry;
    }

    private static String validSummary() {
        return """
                ## Primary Request and Intent
                integration
                ## Current Work
                projection-summary
                ## Pending Tasks
                无
                ## All User Messages
                已覆盖
                ## Key Technical Concepts
                source/model view
                ## Files and Code Sections
                无
                ## Problem Solving
                无
                ## Errors and Fixes
                无
                ## Optional Next Step
                继续
                """.trim();
    }

    private static final class ProjectionAwareClient implements LlmClient {
        private final boolean replayReasoning;
        private final List<List<Message>> businessRequests = new ArrayList<>();

        private ProjectionAwareClient(boolean replayReasoning) {
            this.replayReasoning = replayReasoning;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            if (isProjectionSummaryRequest(messages)) {
                return new ChatResponse("assistant", validSummary(), null, 20, 10);
            }
            if (tools == null || tools.isEmpty()) {
                return new ChatResponse("assistant", "auxiliary-summary", null, 20, 10);
            }
            businessRequests.add(List.copyOf(messages));
            return new ChatResponse("assistant", "business-answer", null, 20, 10);
        }

        private boolean isProjectionSummaryRequest(List<Message> messages) {
            return messages != null && !messages.isEmpty()
                    && messages.get(0).content() != null
                    && messages.get(0).content().contains("对话摘要助手");
        }

        private List<Message> lastBusinessMessages() {
            return businessRequests.isEmpty()
                    ? List.of()
                    : businessRequests.get(businessRequests.size() - 1);
        }

        @Override
        public String getModelName() {
            return replayReasoning ? "kimi-test" : "test-model";
        }

        @Override
        public String getProviderName() {
            return replayReasoning ? "kimi" : "test";
        }

        @Override
        public int maxContextWindow() {
            return 8_000;
        }

        @Override
        public boolean replaysReasoningContent() {
            return replayReasoning;
        }
    }
}
