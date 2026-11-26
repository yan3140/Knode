package com.knode.search.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.AuthFailure;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.config.SearchClientsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class HttpAdminAuthorityClient implements AdminAuthorityClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAdminAuthorityClient.class);
    private static final String ADMIN_ME_PATH = "/api/admin/auth/me";
    private static final int MAX_LIST_SIZE = 1000;
    private static final int MAX_ITEM_LENGTH = 128;

    private final HttpClient httpClient;
    private final ObjectMapper strictMapper;
    private final SearchClientsProperties clientsProperties;
    private final SearchAuthProperties.AdminProps adminProps;

    public HttpAdminAuthorityClient(
            SearchClientsProperties clientsProperties,
            SearchAuthProperties authProperties) {
        this.clientsProperties = clientsProperties;
        this.adminProps = authProperties == null ? new SearchAuthProperties.AdminProps() : authProperties.getAdmin();
        long connectMs = Math.max(100L, this.adminProps.getMeConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
        int maxBytes = Math.max(1024, this.adminProps.getMeMaxResponseBytes());
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(maxBytes)
                        .maxStringLength(65_536)
                        .maxNestingDepth(16)
                        .build())
                .build();
        this.strictMapper = new ObjectMapper(factory);
    }

    @Override
    public AdminAuthority fetchCurrentAuthority(String rawAdminToken, String requestId) {
        if (!StringUtils.hasText(rawAdminToken)) {
            throw new AuthFailure(401);
        }
        String origin = resolveValidatedUserOrigin();
        URI uri = URI.create(origin + ADMIN_ME_PATH);
        Duration timeout = Duration.ofMillis(Math.max(100L, adminProps.getMeTimeoutMs()));

        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("token", rawAdminToken)
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.hasText(requestId)) {
            req.header("X-Request-Id", requestId);
        }

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("event=search_admin_authority_interrupted");
            throw new AuthFailure(503);
        } catch (IOException ioEx) {
            LOGGER.warn("event=search_admin_authority_io_failed errorType={}", ioEx.getClass().getSimpleName());
            throw new AuthFailure(503);
        }

        int status = response.statusCode();
        if (status == 401) {
            throw new AuthFailure(401);
        }
        if (status == 403) {
            throw new AuthFailure(403);
        }
        if (status != 200) {
            LOGGER.warn("event=search_admin_authority_bad_status httpStatus={}", status);
            throw new AuthFailure(503);
        }

        byte[] bodyBytes = response.body();
        if (bodyBytes == null || bodyBytes.length == 0 || bodyBytes.length > adminProps.getMeMaxResponseBytes()) {
            LOGGER.warn("event=search_admin_authority_bad_body_size size={}", bodyBytes == null ? 0 : bodyBytes.length);
            throw new AuthFailure(503);
        }
        return parseAuthorityResponse(new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private String resolveValidatedUserOrigin() {
        String raw = clientsProperties == null ? null : clientsProperties.getUserOrigin();
        if (!StringUtils.hasText(raw)) {
            throw new AuthFailure(503);
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI parsed;
        try {
            parsed = URI.create(trimmed);
        } catch (Exception ex) {
            throw new AuthFailure(503);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new AuthFailure(503);
        }
        if ("https".equals(scheme)) {
            return trimmed;
        }
        if ("http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host))) {
            return trimmed;
        }
        LOGGER.warn("event=search_admin_authority_insecure_origin host={}", host);
        throw new AuthFailure(503);
    }

    private AdminAuthority parseAuthorityResponse(String body) {
        JsonNode root;
        try {
            root = strictMapper.readTree(body);
        } catch (Exception ex) {
            LOGGER.warn("event=search_admin_authority_malformed_json");
            throw new AuthFailure(503);
        }
        if (root == null || !root.isObject()) {
            throw new AuthFailure(503);
        }
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.isIntegralNumber()) {
            throw new AuthFailure(503);
        }
        int code = codeNode.intValue();
        if (code == 401) {
            throw new AuthFailure(401);
        }
        if (code == 403) {
            throw new AuthFailure(403);
        }
        if (code != 200) {
            throw new AuthFailure(503);
        }
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isObject()) {
            throw new AuthFailure(503);
        }
        JsonNode userNode = dataNode.get("user");
        if (userNode == null || !userNode.isObject()) {
            throw new AuthFailure(503);
        }
        JsonNode idNode = userNode.get("id");
        if (idNode == null || !idNode.isTextual()) {
            throw new AuthFailure(503);
        }
        String canonicalUserId = CanonicalIdParser.normalizePositiveDecimalString(idNode.asText());
        if (canonicalUserId == null) {
            throw new AuthFailure(503);
        }

        // Validate roles bounds when present, but roles NEVER grant permissions.
        JsonNode rolesNode = dataNode.get("roles");
        if (rolesNode != null && !rolesNode.isNull()) {
            parseBoundedDistinctStrings(rolesNode);
        }

        JsonNode permsNode = dataNode.get("permissions");
        Set<String> permissions = (permsNode == null || permsNode.isNull())
                ? Set.of()
                : parseBoundedDistinctStrings(permsNode);

        return new AdminAuthority(canonicalUserId, permissions);
    }

    private static Set<String> parseBoundedDistinctStrings(JsonNode arrayNode) {
        if (!arrayNode.isArray() || arrayNode.size() > MAX_LIST_SIZE) {
            throw new AuthFailure(503);
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : arrayNode) {
            if (item == null || !item.isTextual()) {
                throw new AuthFailure(503);
            }
            String value = item.asText();
            if (value.isBlank() || value.length() > MAX_ITEM_LENGTH) {
                throw new AuthFailure(503);
            }
            result.add(value);
            if (result.size() > MAX_LIST_SIZE) {
                throw new AuthFailure(503);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
