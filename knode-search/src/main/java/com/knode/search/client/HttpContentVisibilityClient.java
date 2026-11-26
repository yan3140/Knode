package com.knode.search.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.model.QueryBudget;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HttpContentVisibilityClient implements ContentVisibilityClient {
    private static final String VISIBILITY_BATCH_PATH = "/internal/v1/content/articles/visibility:batch";
    private static final String CONTENT_AUDIENCE = "knode-content";
    private static final List<String> VISIBILITY_PERMISSIONS = List.of("content:visibility:read");
    private static final String PURPOSE_PUBLIC_SEARCH = "PUBLIC_SEARCH";
    private static final Set<String> VALID_VISIBILITIES = Set.of(
            "PUBLIC", "WITHDRAWN", "DRAFT", "DELETED", "MISSING");
    private static final Set<String> VALID_DECISIONS = Set.of("READABLE", "DENIED");
    private static final Set<String> ALLOWED_ITEM_FIELDS = Set.of(
            "articleId", "sourceRevision", "visibility", "decision");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SearchClientsProperties properties;
    private final ServiceTokenClient serviceTokenClient;

    public HttpContentVisibilityClient(HttpClient searchOutboundHttpClient,
                                       ObjectMapper objectMapper,
                                       SearchClientsProperties properties,
                                       ServiceTokenClient serviceTokenClient) {
        this.httpClient = searchOutboundHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.serviceTokenClient = serviceTokenClient;
    }

    @Override
    public Map<String, VisibilityDecision> checkPublicSearch(
            List<String> articleIds, QueryBudget budget, String requestId) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }
        Set<String> distinctIds = new LinkedHashSet<>();
        for (String id : articleIds) {
            String canonical = CanonicalIdParser.normalizePositiveDecimalString(id);
            if (canonical == null) {
                throw new IllegalArgumentException("Invalid articleId for visibility check");
            }
            distinctIds.add(canonical);
        }
        if (distinctIds.size() > 100) {
            throw new IllegalArgumentException("Visibility batch supports at most 100 article IDs");
        }
        if (!StringUtils.hasText(properties.getContentOrigin())) {
            throw new IllegalStateException("Content visibility client origin is not configured");
        }

        String accessToken = serviceTokenClient.acquireToken(
                CONTENT_AUDIENCE, VISIBILITY_PERMISSIONS, budget, requestId);

        Duration timeout = budget.cap(Duration.ofMillis(Math.max(1L, properties.getContentVisibilityTimeoutMs())));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Query timeout budget exhausted before content visibility request");
        }

        URI uri = URI.create(stripTrailingSlash(properties.getContentOrigin()) + VISIBILITY_BATCH_PATH);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("articleIds", List.copyOf(distinctIds));
        payload.put("purpose", PURPOSE_PUBLIC_SEARCH);

        try {
            String requestBody = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            if (StringUtils.hasText(requestId)) {
                requestBuilder.header("X-Request-Id", requestId);
            }
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401) {
                serviceTokenClient.invalidate(CONTENT_AUDIENCE, VISIBILITY_PERMISSIONS);
                throw new IllegalStateException("Content visibility rejected service token with HTTP 401");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Content visibility endpoint returned HTTP " + response.statusCode());
            }
            return parseAndValidateResponse(response.body(), distinctIds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Content visibility request interrupted", interrupted);
        } catch (IOException ioFailure) {
            throw new IllegalStateException("Content visibility request failed", ioFailure);
        }
    }

    private Map<String, VisibilityDecision> parseAndValidateResponse(String body, Set<String> expectedIds)
            throws IOException {
        if (!StringUtils.hasText(body)) {
            throw new IllegalStateException("Content visibility response body is empty");
        }
        JsonNode root = objectMapper.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Content visibility response must be a JSON object");
        }
        Iterator<String> topFields = root.fieldNames();
        while (topFields.hasNext()) {
            if (!"items".equals(topFields.next())) {
                throw new IllegalStateException("Unexpected top-level field in content visibility response");
            }
        }
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw new IllegalStateException("Content visibility response missing items array");
        }

        Map<String, VisibilityDecision> decisions = new LinkedHashMap<>();
        for (JsonNode item : itemsNode) {
            if (item == null || !item.isObject()) {
                throw new IllegalStateException("Content visibility item must be a JSON object");
            }
            Iterator<String> itemFields = item.fieldNames();
            while (itemFields.hasNext()) {
                if (!ALLOWED_ITEM_FIELDS.contains(itemFields.next())) {
                    throw new IllegalStateException("Unexpected field in content visibility item");
                }
            }
            JsonNode articleIdNode = item.get("articleId");
            JsonNode revisionNode = item.get("sourceRevision");
            JsonNode visibilityNode = item.get("visibility");
            JsonNode decisionNode = item.get("decision");

            if (articleIdNode == null || !articleIdNode.isTextual()) {
                throw new IllegalStateException("Content visibility item missing string articleId");
            }
            String articleId = CanonicalIdParser.normalizePositiveDecimalString(articleIdNode.asText());
            if (articleId == null || !expectedIds.contains(articleId)) {
                throw new IllegalStateException("Unexpected or invalid articleId in content visibility response");
            }
            if (decisions.containsKey(articleId)) {
                throw new IllegalStateException("Duplicate articleId in content visibility response");
            }
            if (visibilityNode == null || !visibilityNode.isTextual()
                    || !VALID_VISIBILITIES.contains(visibilityNode.asText())) {
                throw new IllegalStateException("Invalid visibility in content visibility response");
            }
            if (decisionNode == null || !decisionNode.isTextual()
                    || !VALID_DECISIONS.contains(decisionNode.asText())) {
                throw new IllegalStateException("Invalid decision in content visibility response");
            }

            String visibility = visibilityNode.asText();
            String decision = decisionNode.asText();
            String sourceRevision = null;
            if (revisionNode != null && !revisionNode.isNull()) {
                if (!revisionNode.isTextual()) {
                    throw new IllegalStateException("sourceRevision must be a canonical decimal string");
                }
                sourceRevision = CanonicalIdParser.normalizePositiveDecimalString(revisionNode.asText());
                if (sourceRevision == null) {
                    throw new IllegalStateException("Invalid sourceRevision in content visibility response");
                }
            }

            if ("READABLE".equals(decision)) {
                if (!"PUBLIC".equals(visibility) || sourceRevision == null) {
                    throw new IllegalStateException("READABLE decision requires PUBLIC visibility and sourceRevision");
                }
            }
            if ("MISSING".equals(visibility) && sourceRevision != null) {
                throw new IllegalStateException("MISSING visibility must have null sourceRevision");
            }

            decisions.put(articleId, new VisibilityDecision(articleId, sourceRevision, visibility, decision));
        }

        if (decisions.size() != expectedIds.size()) {
            throw new IllegalStateException("Content visibility response missing requested article IDs");
        }
        return decisions;
    }

    private static String stripTrailingSlash(String origin) {
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
