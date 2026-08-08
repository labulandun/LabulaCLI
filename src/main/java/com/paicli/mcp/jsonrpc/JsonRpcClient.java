package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final java.util.concurrent.ExecutorService CANCELLATION_NOTIFIER =
            Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "paicli-mcp-cancellation");
                thread.setDaemon(true);
                return thread;
            });

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicli-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long timeoutMillis = CancellationContext.remainingMillis(
                TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds)), 0);
        if (timeoutMillis <= 0 || CancellationContext.isCancelled()) {
            throw new IOException("MCP request cancelled or deadline exceeded: " + method);
        }
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
                transport.cancelRequest(id);
                sendCancellationNotification(id, method);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        CancellationToken token = CancellationContext.current();
        CancellationToken.Registration cancellationRegistration = token == null
                ? CancellationToken.Registration.NO_OP
                : token.onCancel(() -> {
                    CompletableFuture<JsonNode> removed = pending.remove(id);
                    if (removed != null) {
                        removed.completeExceptionally(new CancellationException(
                                "JSON-RPC request cancelled: " + method));
                        transport.cancelRequest(id);
                        sendCancellationNotification(id, method);
                    }
                });

        try (cancellationRegistration) {
            transport.send(request);
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("JSON-RPC request interrupted: " + method, e);
        } catch (TimeoutException e) {
            transport.cancelRequest(id);
            sendCancellationNotification(id, method);
            throw new IOException("JSON-RPC request timed out: " + method, e);
        } catch (Exception e) {
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            timeoutTask.cancel(false);
            pending.remove(id);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    private void sendCancellationNotification(long requestId, String requestMethod) {
        CANCELLATION_NOTIFIER.execute(() -> {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("requestId", requestId);
            params.put("reason", "PaiCLI Deadline/cancellation: " + requestMethod);
            try {
                sendNotification("notifications/cancelled", params);
            } catch (Exception ignored) {
                // Best effort: the local future and cancellable transport are already stopped.
            }
        });
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            for (Consumer<JsonNode> listener : notificationListeners) {
                listener.accept(message);
            }
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
