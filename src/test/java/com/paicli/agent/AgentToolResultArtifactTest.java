package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolResultArtifactTest {

    @Test
    void reactHistoryAndNextRequestContainEnvelopeInsteadOfLargeRawResult(@TempDir Path tempDir) {
        String raw = "REACT-RAW-" + "😀中".repeat(50_000);
        ToolRegistry registry = new ToolRegistry(new ToolResultArtifactStore(
                tempDir.resolve("artifacts"), tempDir, 1, 7)) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("large_react_tool".equals(name)) {
                    return raw;
                }
                return super.executeTool(name, argumentsJson);
            }
        };
        registry.setProjectPath(tempDir.toString());
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_react",
                                new LlmClient.ToolCall.Function("large_react_tool", "{}"))),
                        20,
                        5),
                new LlmClient.ChatResponse("assistant", "done", null, 20, 5)
        ));
        Agent agent = new Agent(llm, registry);

        agent.run("run large result tool");

        String requestToolMessage = toolMessage(llm.messagesByCall.get(1));
        String sourceToolMessage = toolMessage(agent.getConversationHistory());
        for (String message : List.of(requestToolMessage, sourceToolMessage)) {
            assertTrue(message.contains("[工具结果已落盘]"));
            assertTrue(message.contains("tool-result://"));
            assertTrue(message.length() < 10_000);
            assertFalse(message.contains(raw));
        }
        String toolMemory = agent.getMemoryManager().getShortTermMemory().getAll().stream()
                .filter(entry -> entry.getType() == com.paicli.memory.MemoryEntry.MemoryType.TOOL_RESULT)
                .map(com.paicli.memory.MemoryEntry::getContent)
                .findFirst()
                .orElseThrow();
        assertTrue(toolMemory.contains("tool-result://"));
        assertFalse(toolMemory.contains(raw));
    }

    private static String toolMessage(List<LlmClient.Message> messages) {
        return messages.stream()
                .filter(message -> "tool".equals(message.role()))
                .map(LlmClient.Message::content)
                .findFirst()
                .orElseThrow();
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("missing response");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
