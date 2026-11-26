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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class StatefulElasticsearchStub implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, StoredDoc> writtenDocuments = new ConcurrentHashMap<>();
    private final Map<String, StoredDoc> refreshedDocuments = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<BiFunction<RecordedRequest, Supplier<StubResponse>, StubResponse>> interceptor =
            new AtomicReference<>();
    private volatile boolean autoRefresh = true;

    public StatefulElasticsearchStub() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newCachedThreadPool();
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setAutoRefresh(boolean enabled) {
        this.autoRefresh = enabled;
    }

    public void refresh(String index) {
        synchronized (writtenDocuments) {
            for (Map.Entry<String, StoredDoc> entry : writtenDocuments.entrySet()) {
                if (entry.getValue().index().equals(index)) {
                    refreshedDocuments.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    public void setInterceptor(BiFunction<RecordedRequest, Supplier<StubResponse>, StubResponse> fn) {
        this.interceptor.set(fn);
    }

    public void clearInterceptor() {
        this.interceptor.set(null);
    }

    public void reset() {
        synchronized (writtenDocuments) {
            writtenDocuments.clear();
            refreshedDocuments.clear();
        }
        requests.clear();
        interceptor.set(null);
        autoRefresh = true;
    }

    public StoredDoc getDocument(String index, String id) {
        return getWrittenDocument(index, id);
    }

    public StoredDoc getWrittenDocument(String index, String id) {
        return writtenDocuments.get(index + "/" + id);
    }

    public StoredDoc getRefreshedDocument(String index, String id) {
        return refreshedDocuments.get(index + "/" + id);
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest req = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                body
        );
        requests.add(req);

        Supplier<StubResponse> defaultAction = () -> dispatch(req);
        BiFunction<RecordedRequest, Supplier<StubResponse>, StubResponse> fn = interceptor.get();
        StubResponse response = fn != null ? fn.apply(req, defaultAction) : defaultAction.get();
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

    private StubResponse dispatch(RecordedRequest req) {
        String[] parts = req.path().split("/");
        Map<String, String> query = parseQuery(req.query());
        // /<index>/_doc/<id>
        if (parts.length == 4 && "_doc".equals(parts[2])) {
            String index = parts[1];
            String id = parts[3];
            if ("PUT".equalsIgnoreCase(req.method())) {
                return handleIndex(index, id, query, req.body());
            }
            if ("GET".equalsIgnoreCase(req.method())) {
                return handleGet(index, id, query);
            }
        }
        // /<index>/_search
        if (parts.length == 3 && "_search".equals(parts[2]) && "POST".equalsIgnoreCase(req.method())) {
            return handleSearch(parts[1], query, req.body());
        }
        return StubResponse.status(404, "{\"error\":\"not_found\"}");
    }

    private StubResponse handleIndex(String index, String id, Map<String, String> query, String body) {
        String key = index + "/" + id;
        String versionParam = query.get("version");
        String versionType = query.get("version_type");
        String refreshParam = query.get("refresh");
        Long requestedVersion = versionParam == null ? null : Long.parseLong(versionParam);

        synchronized (writtenDocuments) {
            StoredDoc existing = writtenDocuments.get(key);
            if ("external".equalsIgnoreCase(versionType) && requestedVersion != null) {
                if (existing != null && existing.version() >= requestedVersion) {
                    String conflictJson = "{\"error\":{\"root_cause\":[{\"type\":\"version_conflict_engine_exception\","
                            + "\"reason\":\"[" + id + "]: version conflict, current version [" + existing.version()
                            + "] is higher or equal to the one provided [" + requestedVersion + "]\"}],"
                            + "\"type\":\"version_conflict_engine_exception\","
                            + "\"reason\":\"[" + id + "]: version conflict\"},\"status\":409}";
                    return StubResponse.status(409, conflictJson);
                }
            }
            long nextVersion = requestedVersion != null
                    ? requestedVersion
                    : (existing == null ? 1L : existing.version() + 1L);
            boolean created = existing == null;
            StoredDoc stored = new StoredDoc(index, id, nextVersion, body);
            writtenDocuments.put(key, stored);
            boolean shouldRefresh = autoRefresh
                    && ("true".equalsIgnoreCase(refreshParam) || "wait_for".equalsIgnoreCase(refreshParam));
            if (shouldRefresh) {
                refreshedDocuments.put(key, stored);
            }
            String result = created ? "created" : "updated";
            String okJson = "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"_version\":" + nextVersion
                    + ",\"result\":\"" + result + "\",\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0},"
                    + "\"_seq_no\":" + nextVersion + ",\"_primary_term\":1}";
            return StubResponse.status(created ? 201 : 200, okJson);
        }
    }

    private StubResponse handleGet(String index, String id, Map<String, String> query) {
        boolean realtime = !"false".equalsIgnoreCase(query.get("realtime"));
        StoredDoc existing = (realtime ? writtenDocuments : refreshedDocuments).get(index + "/" + id);
        if (existing == null) {
            return StubResponse.status(404, "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"found\":false}");
        }
        String okJson = "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"_version\":" + existing.version()
                + ",\"_seq_no\":" + existing.version() + ",\"_primary_term\":1,\"found\":true,\"_source\":"
                + existing.sourceJson() + "}";
        return StubResponse.ok(okJson);
    }

    private StubResponse handleSearch(String index, Map<String, String> queryParams, String body) {
        try {
            JsonNode reqNode = MAPPER.readTree(body);
            boolean includeVersion = reqNode.path("version").asBoolean(false)
                    || "true".equalsIgnoreCase(queryParams.get("version"));
            JsonNode idsNode = reqNode.path("query").path("ids").path("values");

            ObjectNode response = MAPPER.createObjectNode();
            response.put("took", 1);
            response.put("timed_out", false);
            ObjectNode shards = response.putObject("_shards");
            shards.put("total", 1);
            shards.put("successful", 1);
            shards.put("skipped", 0);
            shards.put("failed", 0);

            ObjectNode hitsObj = response.putObject("hits");
            ArrayNode hitsArr = hitsObj.putArray("hits");
            long count = 0L;

            if (idsNode.isArray()) {
                Set<String> wantedIds = new HashSet<>();
                for (JsonNode val : idsNode) {
                    if (val != null && val.isTextual()) {
                        wantedIds.add(val.asText());
                    }
                }
                for (StoredDoc doc : refreshedDocuments.values()) {
                    if (!doc.index().equals(index) || !wantedIds.contains(doc.id())) {
                        continue;
                    }
                    count++;
                    ObjectNode hit = hitsArr.addObject();
                    hit.put("_index", doc.index());
                    hit.put("_id", doc.id());
                    if (includeVersion) {
                        hit.put("_version", doc.version());
                    }
                    hit.put("_score", 1.0);
                    hit.set("_source", MAPPER.readTree(doc.sourceJson()));
                }
            } else {
                String keyword = reqNode.path("query").path("bool").path("must").get(0)
                        .path("multi_match").path("query").asText("").toLowerCase(Locale.ROOT);
                for (StoredDoc doc : refreshedDocuments.values()) {
                    if (!doc.index().equals(index)) {
                        continue;
                    }
                    JsonNode src = MAPPER.readTree(doc.sourceJson());
                    if (src.has("searchable") && !src.get("searchable").asBoolean(true)) {
                        continue;
                    }
                    String combined = (src.path("title").asText("") + " "
                            + src.path("summary").asText("") + " "
                            + src.path("content").asText("") + " "
                            + src.path("categoryName").asText("") + " "
                            + src.path("tagNames").toString()).toLowerCase(Locale.ROOT);
                    if (!keyword.isEmpty() && !combined.contains(keyword)) {
                        continue;
                    }
                    count++;
                    ObjectNode hit = hitsArr.addObject();
                    hit.put("_index", doc.index());
                    hit.put("_id", doc.id());
                    if (includeVersion) {
                        hit.put("_version", doc.version());
                    }
                    hit.put("_score", 10.0);
                    hit.set("_source", src);
                }
            }

            ObjectNode total = hitsObj.putObject("total");
            total.put("value", count);
            total.put("relation", "eq");
            return StubResponse.ok(MAPPER.writeValueAsString(response));
        } catch (Exception ex) {
            return StubResponse.status(500, "{\"error\":\"search_stub_failure\"}");
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

    public record StoredDoc(String index, String id, long version, String sourceJson) {
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

        public static StubResponse delayedStatus(int status, String body, long delayMs) {
            return new StubResponse(status, body, delayMs);
        }
    }
}
