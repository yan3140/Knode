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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpServiceTokenClient implements ServiceTokenClient {
    private static final String TOKEN_PATH = "/internal/v1/auth/service-tokens";
    private static final Set<String> ALLOWED_RESPONSE_FIELDS = Set.of(
            "accessToken", "tokenType", "expiresIn", "audience", "permissions");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SearchClientsProperties properties;
    private final Clock clock;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public HttpServiceTokenClient(HttpClient searchOutboundHttpClient,
                                  ObjectMapper objectMapper,
                                  SearchClientsProperties properties,
                                  Clock clock) {
        this.httpClient = searchOutboundHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
        if (!StringUtils.hasText(audience) || permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("Audience and permissions must be non-empty");
        }
        String cacheKey = cacheKey(audience, permissions);
        Instant now = clock.instant();
        CachedToken cached = cache.get(cacheKey);
        if (cached != null && now.isBefore(cached.refreshAt())) {
            return cached.accessToken();
        }
        CachedToken fetched = requestToken(audience, permissions, budget, requestId);
        cache.put(cacheKey, fetched);
        return fetched.accessToken();
    }

    @Override
    public void invalidate(String audience, List<String> permissions) {
        if (!StringUtils.hasText(audience) || permissions == null || permissions.isEmpty()) {
            return;
        }
        cache.remove(cacheKey(audience, permissions));
    }

    private CachedToken requestToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
        if (!StringUtils.hasText(properties.getUserOrigin())
                || !StringUtils.hasText(properties.getServiceId())
                || !StringUtils.hasText(properties.getBootstrapSecret())) {
            throw new IllegalStateException("Service token client is not configured");
        }
        Duration timeout = budget.cap(Duration.ofMillis(Math.max(1L, properties.getUserTokenTimeoutMs())));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("Query timeout budget exhausted before service token request");
        }

        URI uri = URI.create(stripTrailingSlash(properties.getUserOrigin()) + TOKEN_PATH);
        String basic = Base64.getEncoder().encodeToString(
                (properties.getServiceId() + ":" + properties.getBootstrapSecret()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audience", audience);
        payload.put("permissions", permissions);

        try {
            String requestBody = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            if (StringUtils.hasText(requestId)) {
                requestBuilder.header("X-Request-Id", requestId);
            }
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Service token endpoint returned HTTP " + response.statusCode());
            }
            return parseAndValidateResponse(response.body(), audience, permissions);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service token request interrupted", interrupted);
        } catch (IOException ioFailure) {
            throw new IllegalStateException("Service token request failed", ioFailure);
        }
    }

    private CachedToken parseAndValidateResponse(String body, String expectedAudience, List<String> expectedPermissions)
            throws IOException {
        if (!StringUtils.hasText(body)) {
            throw new IllegalStateException("Service token response body is empty");
        }
        JsonNode root = objectMapper.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Service token response must be a JSON object");
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!ALLOWED_RESPONSE_FIELDS.contains(field)) {
                throw new IllegalStateException("Unexpected field in service token response: " + field);
            }
        }
        JsonNode tokenNode = root.get("accessToken");
        JsonNode typeNode = root.get("tokenType");
        JsonNode expiresNode = root.get("expiresIn");
        JsonNode audienceNode = root.get("audience");
        JsonNode permissionsNode = root.get("permissions");

        if (tokenNode == null || !tokenNode.isTextual() || !StringUtils.hasText(tokenNode.asText())) {
            throw new IllegalStateException("Missing or blank accessToken");
        }
        if (typeNode == null || !typeNode.isTextual() || !"Bearer".equals(typeNode.asText())) {
            throw new IllegalStateException("Invalid tokenType");
        }
        if (expiresNode == null || !expiresNode.isIntegralNumber()) {
            throw new IllegalStateException("Invalid expiresIn");
        }
        long expiresIn = expiresNode.longValue();
        if (expiresIn < 1L || expiresIn > 300L) {
            throw new IllegalStateException("expiresIn out of allowed range 1..300");
        }
        if (audienceNode == null || !audienceNode.isTextual() || !expectedAudience.equals(audienceNode.asText())) {
            throw new IllegalStateException("Audience mismatch in service token response");
        }
        if (permissionsNode == null || !permissionsNode.isArray() || permissionsNode.isEmpty()) {
            throw new IllegalStateException("Missing permissions in service token response");
        }
        Set<String> grantedPermissions = new HashSet<>();
        for (JsonNode item : permissionsNode) {
            if (!item.isTextual() || !StringUtils.hasText(item.asText()) || !grantedPermissions.add(item.asText())) {
                throw new IllegalStateException("Invalid or duplicate permission in service token response");
            }
        }
        if (!grantedPermissions.containsAll(expectedPermissions)) {
            throw new IllegalStateException("Granted permissions do not cover requested permissions");
        }

        Instant now = clock.instant();
        long skew = Math.max(0L, properties.getTokenRefreshSkewSeconds());
        long effectiveTtl = expiresIn > skew ? expiresIn - skew : Math.max(1L, expiresIn / 2L);
        return new CachedToken(tokenNode.asText(), now.plusSeconds(effectiveTtl));
    }

    private static String cacheKey(String audience, List<String> permissions) {
        List<String> sorted = permissions.stream().distinct().sorted().toList();
        return audience + "|" + String.join(",", sorted);
    }

    private static String stripTrailingSlash(String origin) {
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record CachedToken(String accessToken, Instant refreshAt) {
    }
}
