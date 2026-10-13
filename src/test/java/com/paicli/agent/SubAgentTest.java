package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTest {

    @Test
    void shouldOnlyEnableToolsForWorker() throws Exception {
        assertFalse(invokeShouldUseTools(new SubAgent("planner", AgentRole.PLANNER,
                new GLMClient("test-key"), new ToolRegistry())));
        assertTrue(invokeShouldUseTools(new SubAgent("worker", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry())));
        assertFalse(invokeShouldUseTools(new SubAgent("reviewer", AgentRole.REVIEWER,
                new GLMClient("test-key"), new ToolRegistry())));
    }

    @Test
    void shouldRouteLateReasoningToSupplementalSection() {
        // 模拟服务器先下发 content、再追加 reasoning 的情况
        ScriptedStreamClient llm = new ScriptedStreamClient(listener -> {
            listener.onContentDelta("最终答案内容");
            listener.onReasoningDelta("这段思考在答案之后才到");
        });
        SubAgent worker = new SubAgent("test-worker", AgentRole.WORKER, llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "随便任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        int contentHeadingIdx = output.indexOf("执行输出");
        int contentBodyIdx = output.indexOf("最终答案内容");
        int supplementalHeadingIdx = output.indexOf("补充思考");
        int lateReasoningIdx = output.indexOf("这段思考在答案之后才到");

        assertTrue(contentHeadingIdx >= 0, "执行输出 heading should appear: " + output);
        assertTrue(contentBodyIdx > contentHeadingIdx, "content body should be under 执行输出");
        assertTrue(supplementalHeadingIdx > contentBodyIdx,
                "late reasoning must appear under 补充思考 heading AFTER content, not mixed in");
        assertTrue(lateReasoningIdx > supplementalHeadingIdx,
                "late reasoning body should follow 补充思考 heading");
    }

    @Test
    void shouldPrintFreshHeadingsAcrossToolIterations() {
        // 两轮迭代：第一轮 content + tool_call（narration），第二轮纯 content（final answer）
        // resetBetweenIterations 被调用后，第二轮应该重新打印「执行思考」和「执行输出」标题
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                // 迭代 1：reasoning + content narration + tool_call
                new CallScript(
                        listener -> {
                            listener.onReasoningDelta("准备调用工具……");
                            listener.onContentDelta("我来调用 list_dir 工具");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来调用 list_dir 工具",
                                "准备调用工具……",
                                List.of(new LlmClient.ToolCall(
                                        "call_1",
                                        new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")
                                )),
                                10, 5
                        )
                ),
                // 迭代 2：reasoning + content（最终答案），无 tool_call
                new CallScript(
                        listener -> {
                            listener.onReasoningDelta("分析完成");
                            listener.onContentDelta("目录列出完毕");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "目录列出完毕",
                                "分析完成",
                                null,
                                8, 3
                        )
                )
        ));
        SubAgent worker = new SubAgent("w1", AgentRole.WORKER, llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        // 两轮各自打印一次「执行思考」
        int firstReasoning = output.indexOf("执行思考");
        int secondReasoning = firstReasoning < 0 ? -1 : output.indexOf("执行思考", firstReasoning + 1);
        assertTrue(firstReasoning >= 0, "第一轮应打印执行思考标题");
        assertTrue(secondReasoning > firstReasoning,
                "工具执行后第二轮应重新打印执行思考标题，实际输出：\n" + output);

        // 「执行输出」同样出现两次
        int firstContent = output.indexOf("执行输出");
        int secondContent = firstContent < 0 ? -1 : output.indexOf("执行输出", firstContent + 1);
        assertTrue(firstContent >= 0, "第一轮应打印执行输出标题");
        assertTrue(secondContent > firstContent,
                "工具执行后第二轮应重新打印执行输出标题，实际输出：\n" + output);
    }

    @Test
    void shouldNotEmitEmptyReasoningHeadingForWhitespaceDeltas() {
        // 仅下发空白 reasoning 然后下发 content —— 不能产生空的"执行思考"标题
        ScriptedStreamClient llm = new ScriptedStreamClient(listener -> {
            listener.onReasoningDelta("  ");
            listener.onReasoningDelta("\n");
            listener.onContentDelta("答案");
        });
        SubAgent worker = new SubAgent("test-worker", AgentRole.WORKER, llm, new ToolRegistry());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        worker.execute(AgentMessage.task("orchestrator", "随便任务"), ps);
        ps.flush();

        String output = baos.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("执行思考"),
                "whitespace-only reasoning should not produce an empty reasoning heading: " + output);
        assertTrue(output.contains("执行输出"), "content heading should still appear");
        assertTrue(output.contains("答案"), "content should still appear");
    }

    @Test
    void workerFeedsOnlyArtifactEnvelopeIntoNextLlmIteration(@TempDir Path tempDir) {
        String raw = "WORKER-RAW-" + "x".repeat(100_000);
        ToolRegistry registry = new ToolRegistry(new ToolResultArtifactStore(
                tempDir.resolve("artifacts"), tempDir, 1, 7)) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("large_worker_tool".equals(name)) {
                    return raw;
                }
                return super.executeTool(name, argumentsJson);
            }
        };
        registry.setProjectPath(tempDir.toString());
        MultiCallStreamClient llm = new MultiCallStreamClient(List.of(
                new CallScript(listener -> { }, new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        null,
                        List.of(new LlmClient.ToolCall(
                                "call_worker",
                                new LlmClient.ToolCall.Function("large_worker_tool", "{}"))),
                        10,
                        5)),
                new CallScript(listener -> { }, new LlmClient.ChatResponse(
                        "assistant", "worker done", null, null, 10, 5))
        ));
        SubAgent worker = new SubAgent("worker", AgentRole.WORKER, llm, registry);

        worker.execute(AgentMessage.task("orchestrator", "run large tool"),
                new PrintStream(new ByteArrayOutputStream()));

        String toolMessage = llm.messagesByCall.get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(LlmClient.Message::content)
                .findFirst()
                .orElseThrow();
        assertTrue(toolMessage.contains("[工具结果已落盘]"));
        assertTrue(toolMessage.contains("tool-result://"));
        assertTrue(toolMessage.length() < 10_000);
        assertFalse(toolMessage.contains(raw));
    }

    private boolean invokeShouldUseTools(SubAgent agent) throws Exception {
        Method method = SubAgent.class.getDeclaredMethod("shouldUseTools");
        method.setAccessible(true);
        return (boolean) method.invoke(agent);
    }

    /**
     * 可编排 delta 下发顺序的 stub GLM 客户端，用于测试流式渲染在异常顺序下的行为。
     */
    private static final class ScriptedStreamClient extends GLMClient {
        private final Consumer<StreamListener> script;

        private ScriptedStreamClient(Consumer<StreamListener> script) {
            super("test-key");
            this.script = script;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            script.accept(listener);
            // 返回空 toolCalls 让 SubAgent 作为最终结果返回
            return new ChatResponse("assistant", "最终答案内容", "这段思考在答案之后才到", null, 10, 5);
        }
    }

    /**
     * 多轮次脚本：每次 chat() 调用按顺序消费一条 CallScript，支持测试 tool-call 分支的后续迭代。
     */
    private record CallScript(Consumer<LlmClient.StreamListener> streamScript, LlmClient.ChatResponse response) {}

    private static final class MultiCallStreamClient extends GLMClient {
        private final java.util.Iterator<CallScript> iter;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private MultiCallStreamClient(List<CallScript> scripts) {
            super("test-key");
            this.iter = scripts.iterator();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            if (!iter.hasNext()) {
                throw new IOException("脚本已耗尽，未预设第 N 次调用");
            }
            CallScript next = iter.next();
            next.streamScript().accept(listener);
            return next.response();
        }
    }
}
