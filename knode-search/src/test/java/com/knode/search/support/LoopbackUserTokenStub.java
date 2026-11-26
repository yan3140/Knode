package com.knode.search.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class LoopbackUserTokenStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private final AtomicReference<Function<RecordedRequest, StubResponse>> handler = new AtomicReference<>();

    public LoopbackUserTokenStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        resetDefaultHandler();
        this.server.createContext("/internal/v1/auth/service-tokens", this::handle);
        this.server.start();
    }

    public void resetDefaultHandler() {
        this.handler.set(req -> {
            int seq = tokenCounter.incrementAndGet();
            String json = "{\"accessToken\":\"svc-token-" + seq
                    + "\",\"tokenType\":\"Bearer\",\"expiresIn\":120,\"audience\":\"knode-content\","
                    + "\"permissions\":[\"content:visibility:read\"]}";
            return StubResponse.ok(json);
        });
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setHandler(Function<RecordedRequest, StubResponse> nextHandler) {
        this.handler.set(nextHandler);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest recorded = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Request-Id"),
                body
        );
        requests.add(recorded);

        StubResponse response = handler.get().apply(recorded);
        if (response.delayMs() > 0L) {
            try {
                Thread.sleep(response.delayMs());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record RecordedRequest(
            String method,
            String path,
            String authorization,
            String requestId,
            String body
    ) {
    }

    public record StubResponse(int status, String body, long delayMs) {
        public static StubResponse ok(String body) {
            return new StubResponse(200, body, 0L);
        }

        public static StubResponse status(int status, String body) {
            return new StubResponse(status, body, 0L);
        }

        public static StubResponse delayedOk(String body, long delayMs) {
            return new StubResponse(200, body, delayMs);
        }
    }
}
