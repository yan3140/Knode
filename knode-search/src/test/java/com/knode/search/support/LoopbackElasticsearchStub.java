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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class LoopbackElasticsearchStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<RecordedRequest, StubResponse>> handler = new AtomicReference<>();

    public LoopbackElasticsearchStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        this.handler.set(req -> StubResponse.ok(
                "{\"took\":1,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},"
                        + "\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}"));
        this.server.createContext("/", this::handle);
        this.server.start();
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
                exchange.getRequestURI().getRawQuery(),
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
        exchange.getResponseHeaders().set("Content-Type", "application/vnd.elasticsearch+json; compatible-with=9");
        exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
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

    public record RecordedRequest(String method, String path, String query, String body) {
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
