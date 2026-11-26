package com.knode.search.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

public final class LoopbackUserAdminAuthStub implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final List<RecordedMeRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<RecordedMeRequest, StubHttpResponse>> handler = new AtomicReference<>();

    public LoopbackUserAdminAuthStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        this.handler.set(req -> okAuthority("1", List.of("admin"), List.of("*:*:*")));
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setHandler(Function<RecordedMeRequest, StubHttpResponse> nextHandler) {
        this.handler.set(nextHandler);
    }

    public void setAuthority(String userId, List<String> roles, List<String> permissions) {
        this.handler.set(req -> okAuthority(userId, roles, permissions));
    }

    public int callCount() {
        return callCount.get();
    }

    public void resetCalls() {
        callCount.set(0);
        requests.clear();
    }

    public List<RecordedMeRequest> requests() {
        return List.copyOf(requests);
    }

    public static StubHttpResponse okAuthority(String userId, List<String> roles, List<String> permissions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("code", 200);
        root.put("msg", "操作成功");
        ObjectNode data = root.putObject("data");
        ObjectNode user = data.putObject("user");
        user.put("id", userId);
        user.put("userName", "admin_" + userId);
        ArrayNode rolesNode = data.putArray("roles");
        for (String r : roles) {
            rolesNode.add(r);
        }
        ArrayNode permsNode = data.putArray("permissions");
        for (String p : permissions) {
            permsNode.add(p);
        }
        return new StubHttpResponse(200, root.toString());
    }

    public static StubHttpResponse status(int httpStatus, String body) {
        return new StubHttpResponse(httpStatus, body);
    }

    private void handle(HttpExchange exchange) throws IOException {
        callCount.incrementAndGet();
        RecordedMeRequest req = new RecordedMeRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("token"),
                exchange.getRequestHeaders().getFirst("X-Request-Id")
        );
        requests.add(req);

        StubHttpResponse resp = !"/api/admin/auth/me".equals(req.path()) || !"GET".equalsIgnoreCase(req.method())
                ? new StubHttpResponse(404, "{\"code\":404,\"msg\":\"not found\"}")
                : handler.get().apply(req);

        byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(resp.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record RecordedMeRequest(String method, String path, String tokenHeader, String requestId) {
    }

    public record StubHttpResponse(int status, String body) {
    }
}
