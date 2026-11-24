package com.knode.content.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.content.profile.UserProfileClient;
import com.knode.content.runtime.OutboundContext;
import com.knode.governance.GovernedCall;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PeerOpsClient {
    public enum Peer {
        SEARCH("search", "knode-search"),
        NOTIFICATION("notification", "knode-community"),
        KNOWLEDGE("knowledge", "knode-knowledge");

        private final String domainKey;
        private final String audience;

        Peer(String domainKey, String audience) {
            this.domainKey = domainKey;
            this.audience = audience;
        }

        public String domainKey() { return domainKey; }
        public String audience() { return audience; }
    }

    public record Outcome(String status, Map<String, Object> payload, String reason) {}

    private static final int MAX_BODY_BYTES = 1048576;
    private final Map<Peer, URI> origins = new EnumMap<>(Peer.class);
    private final UserProfileClient users;
    private final GovernedCall governed;
    private final OpenTelemetry telemetry;
    private final HttpClient http = OriginPolicy.newClient();

    public PeerOpsClient(
            @Value("${KNODE_SEARCH_ORIGIN:}") String searchOrigin,
            @Value("${KNODE_COMMUNITY_ORIGIN:}") String communityOrigin,
            @Value("${KNODE_KNOWLEDGE_ORIGIN:}") String knowledgeOrigin,
            UserProfileClient users,
            ObjectProvider<GovernedCall> governedProvider,
            ObjectProvider<OpenTelemetry> telemetryProvider) {
        putOrigin(Peer.SEARCH, OriginPolicy.parseOptional(searchOrigin, "search ops client"));
        putOrigin(Peer.NOTIFICATION, OriginPolicy.parseOptional(communityOrigin, "community ops client"));
        putOrigin(Peer.KNOWLEDGE, OriginPolicy.parseOptional(knowledgeOrigin, "knowledge ops client"));
        this.users = users;
        this.governed = governedProvider == null ? null : governedProvider.getIfAvailable();
        this.telemetry = telemetryProvider == null ? null : telemetryProvider.getIfAvailable();
    }

    private void putOrigin(Peer peer, URI uri) {
        if (uri != null) origins.put(peer, uri);
    }

    public boolean isConfigured(Peer peer) {
        return origins.containsKey(peer) && users.isConfigured();
    }

    public Outcome summary(Peer peer) {
        URI origin = origins.get(peer);
        if (origin == null || !users.isConfigured()) {
            return new Outcome("UNCONFIGURED", null, "Peer ops origin or service credentials not configured");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            String token = users.issueServiceToken(peer.audience(), List.of("ops:read"), deadline);
            HttpResponse<byte[]> response = send(origin.resolve("/internal/v1/ops/consumers/summary"), "GET", token, null, deadline);
            if (response.statusCode() != 200) {
                return new Outcome("UNAVAILABLE", null, "Peer ops summary unavailable");
            }
            JsonNode root = OriginPolicy.JSON.readTree(response.body());
            if (root == null || !root.isObject() || root.isEmpty()) {
                return new Outcome("UNAVAILABLE", null, "Invalid peer ops summary payload");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OriginPolicy.JSON.convertValue(root, LinkedHashMap.class);
            return new Outcome("AVAILABLE", parsed, null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Outcome("UNAVAILABLE", null, "Peer ops summary request failed");
        }
    }

    public Outcome events(Peer peer, int pageNum, int pageSize, String status) {
        URI origin = origins.get(peer);
        if (origin == null || !users.isConfigured()) {
            return new Outcome("UNCONFIGURED", null, "Peer ops origin or service credentials not configured");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            String token = users.issueServiceToken(peer.audience(), List.of("ops:read"), deadline);
            StringBuilder query = new StringBuilder("/internal/v1/ops/consumers/events?pageNum=")
                    .append(pageNum).append("&pageSize=").append(pageSize);
            if (status != null && !status.isBlank()) {
                query.append("&status=").append(URLEncoder.encode(status, StandardCharsets.UTF_8));
            }
            HttpResponse<byte[]> response = send(origin.resolve(query.toString()), "GET", token, null, deadline);
            if (response.statusCode() == 400) return new Outcome("INVALID_REQUEST", null, "Invalid consumer query");
            if (response.statusCode() != 200) return new Outcome("UNAVAILABLE", null, "Peer consumer events unavailable");
            JsonNode root = OriginPolicy.JSON.readTree(response.body());
            if (root == null || !root.isObject() || !root.path("rows").isArray() || !root.path("total").isTextual()
                    || !OriginPolicy.validNonNegativeCount(root.path("total").asText())) {
                return new Outcome("UNAVAILABLE", null, "Invalid peer consumer events payload");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = OriginPolicy.JSON.convertValue(root, LinkedHashMap.class);
            return new Outcome("AVAILABLE", parsed, null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Outcome("UNAVAILABLE", null, "Peer consumer events request failed");
        }
    }

    public Outcome retry(Peer peer, String replayRequestId, String operator, String eventId) {
        URI origin = origins.get(peer);
        if (origin == null || !users.isConfigured()) {
            return new Outcome("UNCONFIGURED", null, "Peer ops origin or service credentials not configured");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        try {
            String token = users.issueServiceToken(peer.audience(), List.of("ops:retry"), deadline);
            String path = eventId == null
                    ? "/internal/v1/ops/consumers/retry"
                    : "/internal/v1/ops/consumers/" + eventId + "/retry";
            String body = OriginPolicy.JSON.writeValueAsString(Map.of(
                    "replayRequestId", replayRequestId,
                    "operator", operator));
            HttpResponse<byte[]> response = send(origin.resolve(path), "POST", token, body, deadline);
            int status = response.statusCode();
            if (status == 200 || status == 202) {
                JsonNode root = OriginPolicy.JSON.readTree(response.body());
                if (!validRetryPayload(root)) {
                    return new Outcome("UNAVAILABLE", null, "Invalid peer retry payload");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = OriginPolicy.JSON.convertValue(root, LinkedHashMap.class);
                return new Outcome("ACCEPTED", parsed, null);
            }
            if (status == 400) return new Outcome("INVALID_REQUEST", null, "Peer rejected retry input");
            if (status == 404) return new Outcome("NOT_FOUND", null, "Event not found in consumer domain");
            if (status == 409) return new Outcome("CONFLICT", null, "Replay conflict in consumer domain");
            return new Outcome("UNAVAILABLE", null, "Peer consumer retry unavailable");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Outcome("UNAVAILABLE", null, "Peer consumer retry request failed");
        }
    }

    private static final Set<String> ACCEPTED_RETRY_STATUSES =
            Set.of("ACCEPTED", "RETRIED", "REPLAYED", "QUEUED", "OK", "NOOP");
    private static final List<String> RETRY_COUNT_FIELDS = List.of("requeued", "retried", "accepted");

    private static boolean validRetryPayload(JsonNode root) {
        if (root == null || !root.isObject() || root.isEmpty()) {
            return false;
        }
        if (root.has("error") && !root.get("error").isNull()) {
            return false;
        }
        if (root.has("code")) {
            JsonNode code = root.get("code");
            if (!code.isIntegralNumber() || (code.asInt() != 200 && code.asInt() != 202)) {
                return false;
            }
        }
        boolean hasPositiveAck = false;
        if (root.has("status")) {
            JsonNode statusNode = root.get("status");
            if (!statusNode.isTextual() || !ACCEPTED_RETRY_STATUSES.contains(statusNode.asText())) {
                return false;
            }
            hasPositiveAck = true;
        }
        for (String countField : RETRY_COUNT_FIELDS) {
            if (root.has(countField)) {
                JsonNode countNode = root.get(countField);
                if (!countNode.isTextual() || !OriginPolicy.validNonNegativeCount(countNode.asText())) {
                    return false;
                }
                hasPositiveAck = true;
            }
        }
        return hasPositiveAck;
    }

    private HttpResponse<byte[]> send(URI uri, String method, String bearer, String body, long deadline) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new TimeoutException();
        long timeout = Math.min(1000, remaining);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeout))
                .header("Authorization", "Bearer " + bearer);
        OutboundContext.injectHeaders(builder, telemetry);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        String resource = "feign:knode-ops#" + method.toLowerCase();
        return OutboundContext.govern(governed, resource, () -> {
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), ignored -> new OriginPolicy.BoundedBody(MAX_BODY_BYTES));
            try {
                return pending.get(timeout, TimeUnit.MILLISECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        });
    }
}
