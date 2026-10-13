package com.paicli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime API 的黑盒客户端：threads / turns / events(SSE 回放) 三个端点。
 * 事件协议见 docs/phase-20-runtime-api.md；trace_id 回传见 docs/langfuse-mvp-observability.md。
 */
public final class RuntimeApiClient {

    public record SseEvent(long id, String type, String data) {
    }

    /** status: completed / failed / timeout。traceId 仅在被测进程开启 OTel 时存在。 */
    public record TurnOutcome(String status, String finalAnswer, String error,
                              String traceId, String rootObservationId) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_INTERVAL_MILLIS = 2000;

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public RuntimeApiClient(int port, String apiKey) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = "http://127.0.0.1:" + port;
        this.apiKey = apiKey;
    }

    public String createThread() throws IOException, InterruptedException {
        JsonNode node = postJson("/v1/threads", "{}", 200);
        return node.path("id").asText();
    }

    public String submitTurn(String threadId, String input) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("input", input);
        JsonNode node = postJson("/v1/threads/" + threadId + "/turns", body.toString(), 202);
        return node.path("id").asText();
    }

    /** 轮询事件直到 turn.completed / turn.failed 或超时。message.delta 内容拼成 finalAnswer。 */
    public TurnOutcome awaitTerminal(String threadId, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long lastEventId = 0;
        StringBuilder answer = new StringBuilder();
        while (System.nanoTime() < deadline) {
            for (SseEvent event : fetchEvents(threadId, lastEventId)) {
                lastEventId = Math.max(lastEventId, event.id());
                JsonNode data = MAPPER.readTree(event.data());
                switch (event.type()) {
                    case "message.delta" -> answer.append(data.path("content").asText(""));
                    case "turn.completed" -> {
                        return new TurnOutcome("completed", answer.toString(), null,
                                textOrNull(data, "trace_id"), textOrNull(data, "root_observation_id"));
                    }
                    case "turn.failed" -> {
                        return new TurnOutcome("failed", answer.toString(),
                                data.path("error").asText(""),
                                textOrNull(data, "trace_id"), textOrNull(data, "root_observation_id"));
                    }
                    default -> {
                        // thread.created / turn.started：无需处理
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return new TurnOutcome("timeout", answer.toString(), "评测超时", null, null);
    }

    public List<SseEvent> fetchEvents(String threadId, long afterId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/v1/threads/" + threadId + "/events?after=" + afterId)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("events 拉取失败: HTTP " + response.statusCode() + " " + response.body());
        }
        return parseSse(response.body());
    }

    /** 解析 phase-20 Runtime API 的 SSE 回放格式：id / event / data 三行一组，空行分隔。 */
    static List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        long id = 0;
        String type = null;
        String data = null;
        for (String line : body.split("\n", -1)) {
            line = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (line.startsWith("id: ")) {
                id = Long.parseLong(line.substring(4).trim());
            } else if (line.startsWith("event: ")) {
                type = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
                data = line.substring(6);
            } else if (line.isEmpty() && type != null) {
                events.add(new SseEvent(id, type, data == null ? "{}" : data));
                id = 0;
                type = null;
                data = null;
            }
        }
        if (type != null) {
            events.add(new SseEvent(id, type, data == null ? "{}" : data));
        }
        return events;
    }

    private JsonNode postJson(String path, String body, int expectedStatus)
            throws IOException, InterruptedException {
        HttpRequest request = requestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expectedStatus) {
            throw new IOException(path + " 失败: HTTP " + response.statusCode() + " " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
