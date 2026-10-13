package com.paicli.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiClientTest {

    @Test
    void parseSseSplitsEventsByBlankLine() {
        String body = """
                id: 1
                event: turn.started
                data: {"turn_id":"t1","input":"hi"}

                id: 2
                event: message.delta
                data: {"turn_id":"t1","content":"answer"}

                id: 3
                event: turn.completed
                data: {"turn_id":"t1","status":"completed","trace_id":"abc"}

                """;
        List<RuntimeApiClient.SseEvent> events = RuntimeApiClient.parseSse(body);
        assertEquals(3, events.size());
        assertEquals(1, events.get(0).id());
        assertEquals("turn.started", events.get(0).type());
        assertEquals("message.delta", events.get(1).type());
        assertTrue(events.get(1).data().contains("\"content\":\"answer\""));
        assertEquals(3, events.get(2).id());
        assertTrue(events.get(2).data().contains("\"trace_id\":\"abc\""));
    }

    @Test
    void parseSseHandlesEmptyBodyAndCrlf() {
        assertTrue(RuntimeApiClient.parseSse("").isEmpty());
        String crlf = "id: 7\r\nevent: turn.completed\r\ndata: {\"a\":1}\r\n\r\n";
        List<RuntimeApiClient.SseEvent> events = RuntimeApiClient.parseSse(crlf);
        assertEquals(1, events.size());
        assertEquals(7, events.get(0).id());
        assertEquals("{\"a\":1}", events.get(0).data());
    }

    @Test
    void parseSseKeepsUnterminatedLastEvent() {
        String body = "id: 5\nevent: message.delta\ndata: {\"content\":\"x\"}";
        List<RuntimeApiClient.SseEvent> events = RuntimeApiClient.parseSse(body);
        assertEquals(1, events.size());
        assertEquals("message.delta", events.get(0).type());
    }
}
