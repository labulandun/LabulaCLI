package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ContextCompressorRequestBudgetTest {

    @Test
    void oversizedMapRequestFallsBackWithoutCallingProvider() {
        TinyClient client = new TinyClient();
        ContextCompressor compressor = new ContextCompressor(client, 1);
        ConversationMemory memory = new ConversationMemory(100_000);
        memory.store(entry("old", "x".repeat(30_000)));
        memory.store(entry("recent", "keep"));

        String result = compressor.compress(memory);

        assertNotNull(result);
        assertEquals(0, client.calls,
                "short-term compressor must apply its hard gate before the provider");
    }

    private static MemoryEntry entry(String id, String content) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.CONVERSATION,
                null, MemoryEntry.estimateTokens(content));
    }

    private static final class TinyClient implements LlmClient {
        private int calls;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            return new ChatResponse("assistant", "unexpected", null, 1, 1);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            calls++;
            return new ChatResponse("assistant", "unexpected", null, 1, 1);
        }

        @Override
        public String getModelName() {
            return "tiny-compressor";
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
}
