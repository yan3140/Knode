package com.knode.search.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class LoopbackContentSnapshotStub implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<RecordedHttpRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, SnapshotState> snapshotsById = new LinkedHashMap<>();
    private final Map<String, SnapshotState> snapshotsByClientReqId = new LinkedHashMap<>();
    private final List<CommitEntry> commits = new ArrayList<>();
    private final Set<String> expiredSnapshotIds = ConcurrentHashMap.newKeySet();
    private final Set<String> expiredChangeCursors = ConcurrentHashMap.newKeySet();
    private final AtomicInteger snapshotSeq = new AtomicInteger(0);
    private final AtomicLong commitSeq = new AtomicLong(0);
    private final AtomicReference<String> expectedBearerToken = new AtomicReference<>("snapshot-service-token");
    private final AtomicReference<BiFunction<RecordedHttpRequest, Supplier<HttpStubResponse>, HttpStubResponse>> interceptor =
            new AtomicReference<>();
    private volatile List<ObjectNode> currentLivePublicArticles = List.of();
    private final Map<Long, ObjectNode> currentArticleBatchItems = new ConcurrentHashMap<>();
    private volatile Instant defaultExpiresAt = Instant.parse("2026-12-01T00:00:00Z");

    public LoopbackContentSnapshotStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setExpectedBearerToken(String token) {
        this.expectedBearerToken.set(token);
    }

    public void setLivePublicArticles(List<ObjectNode> articles) {
        synchronized (this) {
            this.currentLivePublicArticles = List.copyOf(articles);
        }
    }

    public void putCurrentPublicArticle(ObjectNode snapshotNode) {
        long id = Long.parseLong(snapshotNode.path("articleId").asText());
        ObjectNode item = MAPPER.createObjectNode();
        item.put("articleId", Long.toString(id));
        item.put("visibility", "PUBLIC");
        item.put("sourceRevision", snapshotNode.path("sourceRevision").asText());
        item.put("decision", "READABLE");
        item.set("snapshot", snapshotNode.deepCopy());
        currentArticleBatchItems.put(id, item);
    }

    public void putCurrentHiddenArticle(long articleId, String visibility, long sourceRevision) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("articleId", Long.toString(articleId));
        item.put("visibility", visibility);
        item.put("sourceRevision", Long.toString(sourceRevision));
        item.put("decision", "DENIED");
        item.putNull("snapshot");
        currentArticleBatchItems.put(articleId, item);
    }

    public void removeCurrentArticle(long articleId) {
        currentArticleBatchItems.remove(articleId);
    }

    public void clearCurrentArticles() {
        currentArticleBatchItems.clear();
    }

    public synchronized long appendCommitBatch(List<JsonNode> eventNodes) {
        long seq = commitSeq.incrementAndGet();
        commits.add(new CommitEntry(seq, List.copyOf(eventNodes)));
        return seq;
    }

    public void expireSnapshot(String snapshotId) {
        expiredSnapshotIds.add(snapshotId);
    }

    public void expireChangeCursor(String cursor) {
        expiredChangeCursors.add(cursor);
    }

    public void setInterceptor(BiFunction<RecordedHttpRequest, Supplier<HttpStubResponse>, HttpStubResponse> fn) {
        this.interceptor.set(fn);
    }

    public void clearInterceptor() {
        this.interceptor.set(null);
    }

    public List<RecordedHttpRequest> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        RecordedHttpRequest req = new RecordedHttpRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                auth,
                requestId,
                body
        );
        requests.add(req);

        Supplier<HttpStubResponse> defaultAction = () -> dispatch(req);
        BiFunction<RecordedHttpRequest, Supplier<HttpStubResponse>, HttpStubResponse> fn = interceptor.get();
        HttpStubResponse resp = fn != null ? fn.apply(req, defaultAction) : defaultAction.get();

        byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (resp.retryAfterSeconds() != null) {
            exchange.getResponseHeaders().set("Retry-After", Long.toString(resp.retryAfterSeconds()));
        }
        exchange.sendResponseHeaders(resp.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private synchronized HttpStubResponse dispatch(RecordedHttpRequest req) {
        String expected = expectedBearerToken.get();
        if (expected != null && !("Bearer " + expected).equals(req.authorization())) {
            return HttpStubResponse.status(401, "{\"error\":{\"code\":\"UNAUTHORIZED\"}}");
        }
        String path = req.path();
        Map<String, String> query = parseQuery(req.query());

        if ("POST".equalsIgnoreCase(req.method()) && "/internal/v1/content/article-snapshots".equals(path)) {
            return handleCreateSnapshot(req.body());
        }
        if ("GET".equalsIgnoreCase(req.method()) && path.startsWith("/internal/v1/content/article-snapshots/")) {
            String snapshotId = URLDecoder.decode(
                    path.substring("/internal/v1/content/article-snapshots/".length()), StandardCharsets.UTF_8);
            return handleFetchSnapshotPage(snapshotId, query);
        }
        if ("GET".equalsIgnoreCase(req.method()) && "/internal/v1/content/article-changes".equals(path)) {
            return handleFetchArticleChanges(query);
        }
        if ("POST".equalsIgnoreCase(req.method()) && "/internal/v1/content/articles/snapshots:batch".equals(path)) {
            return handleFetchSnapshotsBatch(req.body());
        }
        if ("POST".equalsIgnoreCase(req.method()) && "/internal/v1/content/articles/visibility:batch".equals(path)) {
            return handleFetchVisibilityBatch(req.body());
        }
        return HttpStubResponse.status(404, "{\"error\":{\"code\":\"NOT_FOUND\"}}");
    }

    private HttpStubResponse handleFetchVisibilityBatch(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode ids = root.get("articleIds");
            if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 100) {
                return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_ARGUMENT\"}}");
            }
            ObjectNode resp = MAPPER.createObjectNode();
            ArrayNode items = resp.putArray("items");
            for (JsonNode idNode : ids) {
                long id = Long.parseLong(idNode.asText());
                ObjectNode state = currentArticleBatchItems.get(id);
                ObjectNode outItem = MAPPER.createObjectNode();
                outItem.put("articleId", Long.toString(id));
                if (state != null) {
                    if (state.get("sourceRevision") == null || state.get("sourceRevision").isNull()) {
                        outItem.putNull("sourceRevision");
                    } else {
                        outItem.put("sourceRevision", state.path("sourceRevision").asText());
                    }
                    outItem.put("visibility", state.path("visibility").asText());
                    outItem.put("decision", state.path("decision").asText());
                } else {
                    outItem.putNull("sourceRevision");
                    outItem.put("visibility", "MISSING");
                    outItem.put("decision", "DENIED");
                }
                items.add(outItem);
            }
            return HttpStubResponse.ok(resp.toString());
        } catch (Exception ex) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"BAD_JSON\"}}");
        }
    }

    private HttpStubResponse handleFetchSnapshotsBatch(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode ids = root.get("articleIds");
            if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 100) {
                return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_ARGUMENT\"}}");
            }
            ObjectNode resp = MAPPER.createObjectNode();
            ArrayNode items = resp.putArray("items");
            for (JsonNode idNode : ids) {
                if (!idNode.isTextual()) {
                    return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_ARGUMENT\"}}");
                }
                long id = Long.parseLong(idNode.asText());
                ObjectNode state = currentArticleBatchItems.get(id);
                if (state != null) {
                    items.add(state.deepCopy());
                } else {
                    ObjectNode missing = MAPPER.createObjectNode();
                    missing.put("articleId", Long.toString(id));
                    missing.put("visibility", "MISSING");
                    missing.putNull("sourceRevision");
                    missing.put("decision", "DENIED");
                    missing.putNull("snapshot");
                    items.add(missing);
                }
            }
            return HttpStubResponse.ok(resp.toString());
        } catch (Exception ex) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"BAD_JSON\"}}");
        }
    }

    private HttpStubResponse handleCreateSnapshot(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String clientRequestId = root.path("clientRequestId").asText("");
            String purpose = root.path("purpose").asText("");
            if (clientRequestId.isBlank() || !"SEARCH_REBUILD".equals(purpose)) {
                return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_ARGUMENT\"}}");
            }
            SnapshotState existing = snapshotsByClientReqId.get(clientRequestId);
            if (existing != null && !expiredSnapshotIds.contains(existing.snapshotId())) {
                return serializeSnapshotHandle(existing);
            }
            String snapshotId = "snap_" + snapshotSeq.incrementAndGet();
            long baseCommitSeq = commitSeq.get();
            String incCursor = encodeChangeCursor(baseCommitSeq, snapshotId);
            SnapshotState state = new SnapshotState(
                    snapshotId,
                    clientRequestId,
                    List.copyOf(currentLivePublicArticles),
                    incCursor,
                    defaultExpiresAt
            );
            snapshotsById.put(snapshotId, state);
            snapshotsByClientReqId.put(clientRequestId, state);
            return serializeSnapshotHandle(state);
        } catch (Exception ex) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"BAD_JSON\"}}");
        }
    }

    private HttpStubResponse serializeSnapshotHandle(SnapshotState state) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("snapshotId", state.snapshotId());
        node.put("incrementalCursor", state.incrementalCursor());
        node.put("expiresAt", state.expiresAt().toString());
        return HttpStubResponse.ok(node.toString());
    }

    private HttpStubResponse handleFetchSnapshotPage(String snapshotId, Map<String, String> query) {
        if (expiredSnapshotIds.contains(snapshotId)) {
            return HttpStubResponse.status(410, "{\"error\":{\"code\":\"SNAPSHOT_EXPIRED\"}}");
        }
        SnapshotState state = snapshotsById.get(snapshotId);
        if (state == null) {
            return HttpStubResponse.status(404, "{\"error\":{\"code\":\"SNAPSHOT_NOT_FOUND\"}}");
        }
        int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
        if (limit < 1 || limit > 100) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_LIMIT\"}}");
        }
        int offset = 0;
        String cursor = query.get("cursor");
        if (cursor != null) {
            offset = decodePageOffset(cursor, snapshotId);
            if (offset < 0) {
                return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_CURSOR\"}}");
            }
        }
        List<ObjectNode> items = state.items();
        int end = Math.min(items.size(), offset + limit);
        boolean done = end >= items.size();
        String nextCursor = done ? null : encodePageOffset(snapshotId, end);

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("snapshotId", state.snapshotId());
        ArrayNode arr = resp.putArray("items");
        for (int i = offset; i < end; i++) {
            arr.add(items.get(i));
        }
        if (nextCursor == null) {
            resp.putNull("nextCursor");
        } else {
            resp.put("nextCursor", nextCursor);
        }
        resp.put("done", done);
        resp.put("incrementalCursor", state.incrementalCursor());
        return HttpStubResponse.ok(resp.toString());
    }

    private HttpStubResponse handleFetchArticleChanges(Map<String, String> query) {
        String cursor = query.get("cursor");
        if (cursor == null || cursor.isBlank()) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"MISSING_CURSOR\"}}");
        }
        if (expiredChangeCursors.contains(cursor)) {
            return HttpStubResponse.status(410, "{\"error\":{\"code\":\"CHANGE_CURSOR_EXPIRED\"}}");
        }
        long afterSeq = decodeChangeCursor(cursor);
        if (afterSeq < 0L) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_CURSOR\"}}");
        }
        int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
        if (limit < 1 || limit > 100) {
            return HttpStubResponse.status(400, "{\"error\":{\"code\":\"INVALID_LIMIT\"}}");
        }

        List<CommitEntry> matching = new ArrayList<>();
        for (CommitEntry c : commits) {
            if (c.commitSequence() > afterSeq) {
                matching.add(c);
            }
        }
        int count = Math.min(matching.size(), limit);
        boolean done = count >= matching.size();
        long newSeq = count > 0 ? matching.get(count - 1).commitSequence() : afterSeq;
        String nextCursor = encodeChangeCursor(newSeq);

        ObjectNode resp = MAPPER.createObjectNode();
        ArrayNode commitsArr = resp.putArray("commits");
        for (int i = 0; i < count; i++) {
            CommitEntry entry = matching.get(i);
            ObjectNode commitObj = commitsArr.addObject();
            commitObj.put("commitSequence", Long.toString(entry.commitSequence()));
            ArrayNode eventsArr = commitObj.putArray("events");
            for (JsonNode ev : entry.events()) {
                eventsArr.add(ev);
            }
        }
        resp.put("nextCursor", nextCursor);
        resp.put("done", done);
        resp.put("cursorExpiresAt", defaultExpiresAt.toString());
        return HttpStubResponse.ok(resp.toString());
    }

    public static ObjectNode snapshotItem(
            long articleId,
            long sourceRevision,
            String title,
            String summary,
            String content,
            Long categoryId,
            String categoryName,
            List<String> tags,
            long viewCount,
            long counterRevision,
            String createTimeIso) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("articleId", Long.toString(articleId));
        node.put("sourceRevision", Long.toString(sourceRevision));
        node.put("publicationFactId", "pub-" + articleId + "-v" + sourceRevision);
        node.put("authorId", "9001");
        node.put("title", title);
        node.put("summary", summary);
        node.put("content", content);
        node.put("thumbnail", "https://cdn.example.com/" + articleId + ".png");
        node.put("isTop", "0");
        node.put("isComment", "1");
        if (categoryId != null && categoryName != null) {
            ObjectNode cat = node.putObject("category");
            cat.put("id", Long.toString(categoryId));
            cat.put("name", categoryName);
        } else {
            node.putNull("category");
        }
        ArrayNode tagsArr = node.putArray("tags");
        for (int i = 0; i < tags.size(); i++) {
            ObjectNode tag = tagsArr.addObject();
            tag.put("id", Long.toString(1000L + i));
            tag.put("name", tags.get(i));
        }
        node.put("createTime", createTimeIso);
        node.put("publishedAt", createTimeIso);
        node.put("viewCount", Long.toString(viewCount));
        node.put("counterRevision", Long.toString(counterRevision));
        return node;
    }

    private static String encodePageOffset(String snapshotId, int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("pg:" + snapshotId + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodePageOffset(String cursor, String expectedSnapshotId) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3 || !"pg".equals(parts[0]) || !expectedSnapshotId.equals(parts[1])) {
                return -1;
            }
            return Integer.parseInt(parts[2]);
        } catch (Exception ex) {
            return -1;
        }
    }

    public static String encodeChangeCursor(long commitSeq) {
        return encodeChangeCursor(commitSeq, "default");
    }

    public static String encodeChangeCursor(long commitSeq, String nonce) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("chg:" + commitSeq + ":" + nonce).getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeChangeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length < 2 || !"chg".equals(parts[0])) {
                return -1L;
            }
            return Long.parseLong(parts[1]);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record SnapshotState(
            String snapshotId,
            String clientRequestId,
            List<ObjectNode> items,
            String incrementalCursor,
            Instant expiresAt) {
    }

    private record CommitEntry(long commitSequence, List<JsonNode> events) {
    }

    public record RecordedHttpRequest(
            String method,
            String path,
            String query,
            String authorization,
            String requestId,
            String body) {
    }

    public record HttpStubResponse(int status, String body, Long retryAfterSeconds) {
        public static HttpStubResponse ok(String body) {
            return new HttpStubResponse(200, body, null);
        }

        public static HttpStubResponse status(int status, String body) {
            return new HttpStubResponse(status, body, null);
        }

        public static HttpStubResponse retryable(int status, String body, long retryAfterSeconds) {
            return new HttpStubResponse(status, body, retryAfterSeconds);
        }
    }
}
