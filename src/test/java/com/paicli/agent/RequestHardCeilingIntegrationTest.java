package com.paicli.agent;

import com.paicli.context.ContextProfile;
import com.paicli.context.ContextViewBuildException;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.memory.HistoryProjectionState;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestHardCeilingIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void reactBlocksOverCeilingRequestBeforeProviderAndKeepsSourceTail() {
        TinyRecordingClient client = new TinyRecordingClient();
        ToolRegistry registry = registry("react");
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        try {
            Agent agent = new Agent(client, registry);

            String result = agent.run("must-preserve-tail " + "x".repeat(10_000));

            assertTrue(result.contains(ContextViewBuildException.CODE), result);
            assertEquals(0, client.calls, "hard ceiling must block the provider/stub call");
            assertTrue(agent.getConversationHistory().stream().anyMatch(message ->
                    message.content() != null && message.content().contains("must-preserve-tail")));
            assertTrue(agent.getLastModelConversationView().isEmpty(),
                    "failed candidates are not reported as views actually sent to the model");
            assertFalse(agent.getHistoryProjectionState().hasCheckpoint());

            IllegalStateException runtimeFailure = assertThrows(
                    IllegalStateException.class,
                    () -> agent.runForResult("runtime-tail " + "y".repeat(10_000)));
            assertTrue(runtimeFailure.getMessage().startsWith(ContextViewBuildException.CODE));
            assertEquals(0, client.calls);
        } finally {
            restoreProperty("paicli.memory.dir", oldMemoryDir);
        }
    }

    @Test
    void workerBlocksOverCeilingRequestBeforeProvider() {
        TinyRecordingClient client = new TinyRecordingClient();
        ToolRegistry registry = registry("worker");
        registry.setContextProfile(ContextProfile.custom(8_000, 4_000));
        SubAgent worker = new SubAgent("worker", AgentRole.WORKER, client, registry);

        AgentMessage result = worker.execute(AgentMessage.task(
                "orchestrator", "worker task " + "x".repeat(10_000)));

        assertEquals(AgentMessage.Type.ERROR, result.type());
        assertTrue(result.content().contains(ContextViewBuildException.CODE), result.content());
        assertEquals(0, client.calls);
        assertTrue(worker.getConversationHistory().stream().anyMatch(message ->
                message.content() != null && message.content().startsWith("worker task")),
                "the oversized worker task must remain in source history");
        assertTrue(worker.getLastModelConversationView().isEmpty());
        assertFalse(worker.getHistoryProjectionState().hasCheckpoint());
    }

    @Test
    void planTaskBlocksOverCeilingRequestBeforeProvider() {
        TinyRecordingClient client = new TinyRecordingClient();
        ToolRegistry registry = registry("plan");
        MemoryManager memoryManager = new MemoryManager(client);
        Planner planner = new MinimalPlanner(client);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                planner,
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute());

        String result = agent.run("查看文件 " + "x".repeat(10_000));

        assertTrue(result.contains(ContextViewBuildException.CODE), result);
        assertEquals(0, client.calls);
    }

    @Test
    void overCeilingTailDoesNotCommitNewCheckpointOrReuseOneThatDropsTheTail() {
        CheckpointRecordingClient client = new CheckpointRecordingClient();
        ToolRegistry registry = registry("checkpoint");
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("checkpoint-memory").toString());
        try {
            Agent agent = new Agent(client, registry);
            for (int i = 0; i < 4; i++) {
                assertEquals("ok", agent.run("round-" + i + " " + "x".repeat(20_000)));
            }
            HistoryProjectionState previousState = agent.getHistoryProjectionState();
            assertTrue(previousState.hasCheckpoint());
            int businessCallsBefore = client.businessCalls;

            String result = agent.run("oversized-tail " + "z".repeat(120_000));

            assertTrue(result.contains(ContextViewBuildException.CODE), result);
            assertEquals(businessCallsBefore, client.businessCalls,
                    "the over-ceiling business candidate must not reach the provider");
            assertEquals(previousState, agent.getHistoryProjectionState(),
                    "hard failure must not commit the candidate checkpoint");
            assertTrue(agent.getConversationHistory().stream().anyMatch(message ->
                    message.content() != null && message.content().startsWith("oversized-tail")));
        } finally {
            restoreProperty("paicli.memory.dir", oldMemoryDir);
        }
    }

    private ToolRegistry registry(String name) {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve(name + "-artifacts"), tempDir, 100_000, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        return registry;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class MinimalPlanner extends Planner {
        private MinimalPlanner(LlmClient client) {
            super(client);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("hard-ceiling-plan", goal);
            plan.addTask(new Task("task_1", "查看文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class TinyRecordingClient implements LlmClient {
        private int calls;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            return new ChatResponse("assistant", "unexpected", null, 1, 1);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            calls++;
            return new ChatResponse("assistant", "unexpected", null, 1, 1);
        }

        @Override
        public String getModelName() {
            return "tiny-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 8_000;
        }
    }

    private static final class CheckpointRecordingClient implements LlmClient {
        private int businessCalls;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return respond(messages, tools);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return respond(messages, tools);
        }

        private ChatResponse respond(List<Message> messages, List<Tool> tools) {
            if (messages != null && !messages.isEmpty()
                    && messages.get(0).content() != null
                    && messages.get(0).content().contains("对话摘要助手")) {
                return new ChatResponse("assistant", validSummary(), null, 10, 5);
            }
            businessCalls++;
            return new ChatResponse("assistant", "ok", null, 10, 5);
        }

        @Override
        public String getModelName() {
            return "checkpoint-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 32_000;
        }
    }

    private static String validSummary() {
        return """
                ## Primary Request and Intent
                integration
                ## Current Work
                request ceiling
                ## Pending Tasks
                无
                ## All User Messages
                已覆盖
                ## Key Technical Concepts
                atomic projection
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
}
