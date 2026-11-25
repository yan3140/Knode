package com.knode.community.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.knode.community.support.OriginPolicy;
import com.knode.community.support.OutboundContext;
import com.knode.governance.GovernedCall;
import io.opentelemetry.api.OpenTelemetry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ContentNotificationMetadataClient {
    public record ArticleMetadata(
            String articleId,
            String sourceRevision,
            String visibility,
            String decision,
            String title) {
        public boolean readablePublic() {
            return "PUBLIC".equals(visibility) && "READABLE".equals(decision) && title != null;
        }
    }

    public record MetadataBatch(boolean available, Map<String, ArticleMetadata> items) {
        public static MetadataBatch unavailable() {
            return new MetadataBatch(false, Map.of());
        }
    }

    private static final String PATH = "/internal/v1/content/articles/notification-metadata:batch";
    private static final String PERMISSION = "content:notification-metadata:read";
    private static final Set<String> VALID_VISIBILITY = Set.of("PUBLIC", "DRAFT", "WITHDRAWN", "DELETED", "MISSING");
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final long TOTAL_BUDGET_MS = 2_000L;

    private final URI origin;
    private final CommunityUserClient users;
    private final GovernedCall governed;
    private final OpenTelemetry telemetry;
    private final HttpClient http = OriginPolicy.newClient();

    @Autowired
    public ContentNotificationMetadataClient(
            @Value("${KNODE_CONTENT_ORIGIN:}") String origin,
            CommunityUserClient users,
            ObjectProvider<GovernedCall> governedProvider,
            ObjectProvider<OpenTelemetry> telemetryProvider) {
        this.origin = OriginPolicy.parseOptional(origin, "content metadata client");
        this.users = users;
        this.governed = governedProvider == null ? null : governedProvider.getIfAvailable();
        this.telemetry = telemetryProvider == null ? null : telemetryProvider.getIfAvailable();
    }

    public boolean isConfigured() {
        return origin != null && users.isConfigured();
    }

    public MetadataBatch lookup(Collection<String> rawArticleIds) {
        if (rawArticleIds == null || rawArticleIds.isEmpty()) {
            return new MetadataBatch(true, Map.of());
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>(rawArticleIds);
        if (distinct.size() > 100
                || distinct.stream().anyMatch(id -> !OriginPolicy.validPositiveId(id))
                || !isConfigured()) {
            return MetadataBatch.unavailable();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS);
        try {
            String serviceToken = users.issueServiceToken("knode-content", List.of(PERMISSION), deadline);
            List<String> orderedIds = new ArrayList<>(distinct);
            String payload = OriginPolicy.JSON.writeValueAsString(Map.of("articleIds", orderedIds));
            JsonNode root = OutboundContext.govern(
                    governed,
                    "feign:knode-content#notificationMetadata",
                    () -> executePost(serviceToken, payload, deadline));
            if (!OriginPolicy.exactFields(root, Set.of("items"))
                    || !root.get("items").isArray()
                    || root.get("items").size() != orderedIds.size()) {
                return MetadataBatch.unavailable();
            }
            Map<String, ArticleMetadata> parsed = new LinkedHashMap<>();
            for (int i = 0; i < orderedIds.size(); i++) {
                JsonNode item = root.get("items").get(i);
                String expectedId = orderedIds.get(i);
                ArticleMetadata meta = parseItem(item, expectedId);
                if (meta == null || parsed.containsKey(expectedId)) {
                    return MetadataBatch.unavailable();
                }
                parsed.put(expectedId, meta);
            }
            return new MetadataBatch(true, Map.copyOf(parsed));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return MetadataBatch.unavailable();
        }
    }

    private JsonNode executePost(String bearerToken, String body, long deadlineNanos) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining <= 0) {
            throw new TimeoutException("Content metadata budget exhausted");
        }
        long timeout = Math.min(1000L, remaining);
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(PATH))
                .timeout(Duration.ofMillis(timeout))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json");
        OutboundContext.injectHeaders(builder, telemetry);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, ignored -> new OriginPolicy.BoundedBody(MAX_BODY_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Content metadata returned HTTP " + response.statusCode());
            }
            JsonNode root = OriginPolicy.JSON.readTree(response.body());
            if (root == null) {
                throw new IllegalArgumentException("Empty content metadata response");
            }
            return root;
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static ArticleMetadata parseItem(JsonNode item, String expectedId) {
        if (item == null || !item.isObject()) {
            return null;
        }
        if (!text(item, "articleId")
                || !expectedId.equals(item.get("articleId").asText())
                || !text(item, "visibility")
                || !text(item, "decision")) {
            return null;
        }
        String visibility = item.get("visibility").asText();
        String decision = item.get("decision").asText();
        if (!VALID_VISIBILITY.contains(visibility)) {
            return null;
        }
        boolean isPublic = "PUBLIC".equals(visibility);
        if (isPublic != "READABLE".equals(decision) || (!isPublic && !"DENIED".equals(decision))) {
            return null;
        }
        if (!item.has("sourceRevision")) {
            return null;
        }
        JsonNode revNode = item.get("sourceRevision");
        String revision;
        if ("MISSING".equals(visibility)) {
            if (!revNode.isNull()) {
                return null;
            }
            revision = null;
        } else {
            if (!revNode.isTextual() || !OriginPolicy.validPositiveId(revNode.asText())) {
                return null;
            }
            revision = revNode.asText();
        }
        if (isPublic) {
            if (!OriginPolicy.exactFields(item, Set.of("articleId", "sourceRevision", "visibility", "decision", "title"))
                    || !text(item, "title")
                    || item.get("title").asText().isEmpty()
                    || item.get("title").asText().length() > 256) {
                return null;
            }
            return new ArticleMetadata(expectedId, revision, visibility, decision, item.get("title").asText());
        }
        if (!OriginPolicy.exactFields(item, Set.of("articleId", "sourceRevision", "visibility", "decision"))) {
            return null;
        }
        return new ArticleMetadata(expectedId, revision, visibility, decision, null);
    }

    private static boolean text(JsonNode node, String key) {
        return node.has(key) && node.get(key).isTextual();
    }
}
