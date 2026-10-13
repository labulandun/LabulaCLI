package com.paicli.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliProcessTest {

    @Test
    void runtimeReadinessRejectsPortThatAcceptsThenCloses() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (var ignored = server.accept()) {
                    // 模拟 Docker 端口代理已开放，但容器内 HTTP 服务尚未就绪。
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertFalse(PaiCliProcess.runtimeApiResponds(server.getLocalPort(), "eval-key"));
            accepted.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void runtimeReadinessAcceptsActualHttpResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/threads", exchange -> {
            byte[] body = "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertTrue(PaiCliProcess.runtimeApiResponds(server.getAddress().getPort(), "eval-key"));
        } finally {
            server.stop(0);
        }
    }
}
