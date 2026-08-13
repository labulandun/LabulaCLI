package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextProfile;
import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWritePlanExecutionArtifactsBackToShortTermMemoryOnly() throws Exception {
        Path sampleFile = Files.createFile(tempDir.resolve("sample.txt"));
        Files.writeString(sampleFile, "plan-memory-content");

        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                )
                        )),
                        120,
                        30
                ),
                new LlmClient.ChatResponse("assistant", "已读取并确认文件内容", null, 140, 40)
        ));

        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                new LongTermMemory(tempDir.resolve("memory-store").toFile())
        );
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("请读取测试文件并确认内容");

        List<String> shortTermContents = memoryManager.getShortTermMemory().getAll().stream()
                .map(entry -> entry.getContent())
                .toList();

        assertTrue(result.contains("计划执行完成"));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("请读取测试文件并确认内容")));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("plan-memory-content")));
        assertTrue(shortTermContents.stream().anyMatch(content -> content.contains("已读取并确认文件内容")));
        assertEquals(0, memoryManager.getLongTermMemory().size());
    }

    @Test
    void shouldNotExtractFactsWhenPlanIsCanceled() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory-store-cancel").toFile());
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                longTermMemory
        );
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("⏹️ 已取消本次计划执行。", result);
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    @Test
    void shouldKeepToolOnlyFallbackBoundedWhenToolResultSpills() throws Exception {
        String raw = "PLAN-RAW-" + "x".repeat(300_000);
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("plan-artifacts"), tempDir, 1, 7);
        ToolRegistry toolRegistry = new ToolRegistry(store) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("large_plan_tool".equals(name)) {
                    return raw;
                }
                return super.executeTool(name, argumentsJson);
            }
        };
        toolRegistry.setProjectPath(tempDir.toString());
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_large",
                                new LlmClient.ToolCall.Function("large_plan_tool", "{}"))),
                        100,
                        10),
                new LlmClient.ChatResponse("assistant", "", null, 100, 10)
        ));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute());

        String result = agent.run("执行大结果工具");

        assertTrue(result.contains("artifact_manifest: tool-result://"));
        assertTrue(result.contains("handle: tool-result://"));
        assertTrue(result.length() < 30_000);
        assertFalse(result.contains(raw));
    }

    @Test
    void taskUsesProjectedModelViewAndDropsImagesAndCheckpointBeforeNextTask() throws Exception {
        ToolResultArtifactStore store = new ToolResultArtifactStore(
                tempDir.resolve("plan-projection-artifacts"), tempDir, 100_000, 7);
        ToolRegistry registry = new ToolRegistry(store);
        registry.setProjectPath(tempDir.toString());
        String imagePayload = "A".repeat(3_000_000);
        registry.registerMcpToolOutput(new McpToolDescriptor(
                        "demo",
                        "image",
                        "mcp__demo__image",
                        "returns a test image",
                        new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{}}")),
                ignored -> new ToolOutput(
                        "image result",
                        List.of(LlmClient.ContentPart.imageBase64(imagePayload, "image/png"))));
        ImageProjectionClient client = new ImageProjectionClient();
        MemoryManager projectionMemory = new MemoryManager(client);
        projectionMemory.applyContextProfile(ContextProfile.custom(14_000, 6_000, 0, 0));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                client,
                registry,
                new StubPlanner(client),
                projectionMemory,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute());

        assertTrue(agent.run("first image task").contains("计划执行完成"));

        assertEquals(1, client.summaryCalls);
        assertNotNull(client.firstTaskFinalRequest);
        assertTrue(client.firstTaskFinalRequest.stream().anyMatch(message -> message.content() != null
                && message.content().contains("[已压缩的历史对话摘要]")));
        assertEquals(3, client.firstTaskFinalRequest.stream()
                .mapToInt(LlmClient.Message::imagePartCount)
                .sum(), "the three retained user rounds must keep their image payloads in-task");

        assertTrue(agent.run("second clean task").contains("计划执行完成"));

        assertEquals(1, client.summaryCalls, "a new Plan task must not reuse the old checkpoint");
        assertNotNull(client.secondTaskRequest);
        assertEquals(0, client.secondTaskRequest.stream().mapToInt(LlmClient.Message::imagePartCount).sum());
        assertFalse(client.secondTaskRequest.stream().anyMatch(message -> message.content() != null
                && message.content().contains("[已压缩的历史对话摘要]")));
    }

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<StubResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }
    }

    private static final class ImageProjectionClient implements LlmClient {
        private int firstTaskBusinessCalls;
        private int summaryCalls;
        private boolean firstTaskComplete;
        private List<Message> firstTaskFinalRequest;
        private List<Message> secondTaskRequest;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (messages != null && !messages.isEmpty()
                    && messages.get(0).content() != null
                    && messages.get(0).content().contains("对话摘要助手")) {
                summaryCalls++;
                return new ChatResponse("assistant", validProjectionSummary(), null, 20, 10);
            }
            if (!firstTaskComplete) {
                firstTaskBusinessCalls++;
                if (firstTaskBusinessCalls <= 3) {
                    return new ChatResponse(
                            "assistant",
                            "",
                            List.of(new ToolCall(
                                    "image-call-" + firstTaskBusinessCalls,
                                    new ToolCall.Function("mcp__demo__image", "{}"))),
                            20,
                            10);
                }
                firstTaskComplete = true;
                firstTaskFinalRequest = List.copyOf(messages);
                return new ChatResponse("assistant", "first done", null, 20, 10);
            }
            secondTaskRequest = List.copyOf(messages);
            return new ChatResponse("assistant", "second done", null, 20, 10);
        }

        @Override
        public String getModelName() {
            return "plan-projection-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 32_000;
        }

        private static String validProjectionSummary() {
            return """
                    ## Primary Request and Intent
                    plan image task
                    ## Current Work
                    projected
                    ## Pending Tasks
                    无
                    ## All User Messages
                    已覆盖
                    ## Key Technical Concepts
                    image lifecycle
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
}
