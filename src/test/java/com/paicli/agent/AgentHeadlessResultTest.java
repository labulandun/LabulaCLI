package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentHeadlessResultTest {

    @TempDir
    Path tempDir;

    @Test
    void headlessRunReturnsContentThatWasAlreadyStreamed() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(new StreamingAnswerClient(), registry);

        assertEquals("build output fixed", agent.runForResult("fix the build"));
    }

    private static final class StreamingAnswerClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            listener.onContentDelta("build output fixed");
            return new ChatResponse("assistant", "build output fixed", null, 10, 4);
        }

        @Override
        public String getModelName() {
            return "stream-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
