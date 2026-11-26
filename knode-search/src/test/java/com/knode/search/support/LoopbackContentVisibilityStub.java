package com.knode.search.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.client.VisibilityDecision;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class LoopbackContentVisibilityStub implements AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, VisibilityDecision> decisions = new ConcurrentHashMap<>();
    private final AtomicReference<Function<RecordedRequest, StubResponse>> overrideHandler = new AtomicReference<>();

    public LoopbackContentVisibilityStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        this.server.createContext("/internal/v1/content/articles/visibility:batch", this::handle);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setDecision(String articleId, String sourceRevision, String visibility, String decision) {
        decisions.put(articleId, new VisibilityDecision(articleId, sourceRevision, visibility, decision));
    }

    public void clearDecisions() {
        decisions.clear();
    }

    public void setOverrideHandler(Function<RecordedRequest, StubResponse> handler) {
        overrideHandler.set(handler);
    }

    public void clearOverrideHandler() {
        overrideHandler.set(null);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    public void reset() {
        clearDecisions();
        clearOverrideHandler();
        clearRequests();
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

        Function<RecordedRequest, StubResponse> custom = overrideHandler.get();
        StubResponse response = custom != null ? custom.apply(recorded) : defaultResponse(recorded);
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

    private StubResponse defaultResponse(RecordedRequest recorded) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(recorded.body());
            JsonNode ids = root.get("articleIds");
            List<Map<String, Object>> items = new ArrayList<>();
            if (ids != null && ids.isArray()) {
                for (JsonNode idNode : ids) {
                    String id = idNode.asText();
                    VisibilityDecision decision = decisions.getOrDefault(
                            id,
                            new VisibilityDecision(id, null, "MISSING", "DENIED")
                    );
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("articleId", decision.articleId());
                    item.put("sourceRevision", decision.sourceRevision());
                    item.put("visibility", decision.visibility());
                    item.put("decision", decision.decision());
                    items.add(item);
                }
            }
            return StubResponse.ok(OBJECT_MAPPER.writeValueAsString(Map.of("items", items)));
        } catch (Exception ex) {
            return StubResponse.status(400, "{\"error\":\"BAD_REQUEST\"}");
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
