package com.knode.search.rebuild.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.client.ServiceTokenClient;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.model.QueryBudget;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class HttpContentSnapshotClient implements ContentSnapshotClient {
    public static final String CONTENT_AUDIENCE = "knode-content";
    public static final List<String> SNAPSHOT_PERMISSIONS = List.of("content:snapshot:read");
    public static final String PURPOSE_SEARCH_REBUILD = "SEARCH_REBUILD";
    public static final int MAX_ENVELOPE_BYTES = 10_000_000;

    private static final String SNAPSHOTS_PATH = "/internal/v1/content/article-snapshots";
    private static final String CHANGES_PATH = "/internal/v1/content/article-changes";
    private static final Pattern CLIENT_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Set<String> CREATE_FIELDS = Set.of("snapshotId", "incrementalCursor", "expiresAt");
    private static final Set<String> PAGE_FIELDS = Set.of("snapshotId", "items", "nextCursor", "done", "incrementalCursor");
    private static final Set<String> CHANGES_FIELDS = Set.of("commits", "nextCursor", "done", "cursorExpiresAt");
    private static final Set<String> COMMIT_FIELDS = Set.of("commitSequence", "events");
    private static final Set<String> SNAPSHOT_ITEM_FIELDS = Set.of(
            "articleId", "sourceRevision", "publicationFactId", "authorId", "title", "summary",
            "content", "thumbnail", "isTop", "isComment", "category", "tags",
            "createTime", "publishedAt", "viewCount", "counterRevision"
    );
    private static final Set<String> ID_NAME_FIELDS = Set.of("id", "name");

    private final HttpClient httpClient;
    private final ObjectMapper strictMapper;
    private final SearchClientsProperties properties;
    private final ServiceTokenClient serviceTokenClient;

    public HttpContentSnapshotClient(
            HttpClient searchOutboundHttpClient,
            SearchClientsProperties properties,
            ServiceTokenClient serviceTokenClient) {
        this.httpClient = searchOutboundHttpClient;
        this.properties = properties;
        this.serviceTokenClient = serviceTokenClient;
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_ENVELOPE_BYTES)
                        .maxStringLength(4_000_000)
                        .maxNestingDepth(32)
                        .build())
                .build();
        this.strictMapper = new ObjectMapper(factory);
    }

    @Override
    public SnapshotHandle createSearchRebuildSnapshot(String clientRequestId, QueryBudget budget, String requestId) {
        if (clientRequestId == null || !CLIENT_REQUEST_ID_PATTERN.matcher(clientRequestId).matches()) {
            throw new IllegalArgumentException("clientRequestId must match [A-Za-z0-9._-]{1,64}");
        }
        String origin = requireContentOrigin();
        String token = acquireSnapshotToken(budget, requestId);
        Duration timeout = resolveTimeout(budget);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("clientRequestId", clientRequestId);
        bodyMap.put("purpose", PURPOSE_SEARCH_REBUILD);
        String requestBody;
        try {
            requestBody = strictMapper.writeValueAsString(bodyMap);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize snapshot creation request", ex);
        }

        URI uri = URI.create(origin + SNAPSHOTS_PATH);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (StringUtils.hasText(requestId)) {
            req.header("X-Request-Id", requestId);
        }

        HttpResponse<String> response = send(req.build(), "create_snapshot");
        validateHttpStatus(response, "create_snapshot");
        return parseSnapshotHandle(response.body());
    }

    @Override
    public SnapshotPage fetchSnapshotPage(
            String snapshotId, String cursor, int limit, QueryBudget budget, String requestId) {
        if (!StringUtils.hasText(snapshotId)) {
            throw new IllegalArgumentException("snapshotId must be non-blank");
        }
        int boundedLimit = Math.max(1, Math.min(100, limit));
        String origin = requireContentOrigin();
        String token = acquireSnapshotToken(budget, requestId);
        Duration timeout = resolveTimeout(budget);

        String encodedId = URLEncoder.encode(snapshotId.trim(), StandardCharsets.UTF_8);
        StringBuilder url = new StringBuilder(origin)
                .append(SNAPSHOTS_PATH).append('/').append(encodedId)
                .append("?limit=").append(boundedLimit);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.hasText(requestId)) {
            req.header("X-Request-Id", requestId);
        }

        HttpResponse<String> response = send(req.build(), "fetch_snapshot_page");
        validateHttpStatus(response, "fetch_snapshot_page");
        return parseSnapshotPage(response.body(), snapshotId.trim());
    }

    @Override
    public ChangeFeedPage fetchArticleChanges(String cursor, int limit, QueryBudget budget, String requestId) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("incremental cursor must be non-blank");
        }
        int boundedLimit = Math.max(1, Math.min(100, limit));
        String origin = requireContentOrigin();
        String token = acquireSnapshotToken(budget, requestId);
        Duration timeout = resolveTimeout(budget);

        String url = origin + CHANGES_PATH
                + "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
                + "&limit=" + boundedLimit;

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET();
        if (StringUtils.hasText(requestId)) {
            req.header("X-Request-Id", requestId);
        }

        HttpResponse<String> response = send(req.build(), "fetch_article_changes");
        validateHttpStatus(response, "fetch_article_changes");
        return parseChangeFeedPage(response.body());
    }

    private String requireContentOrigin() {
        if (!StringUtils.hasText(properties.getContentOrigin())) {
            throw ContentSnapshotException.nonRetryable(0, "UNCONFIGURED_ORIGIN", "knode.search.clients.content-origin is not configured");
        }
        String origin = properties.getContentOrigin().trim();
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return origin;
    }

    private String acquireSnapshotToken(QueryBudget budget, String requestId) {
        try {
            return serviceTokenClient.acquireToken(CONTENT_AUDIENCE, SNAPSHOT_PERMISSIONS, budget, requestId);
        } catch (Exception ex) {
            throw ContentSnapshotException.retryable(0, "SERVICE_TOKEN_FAILED",
                    "Failed to acquire content:snapshot:read service token", null, ex);
        }
    }

    private Duration resolveTimeout(QueryBudget budget) {
        Duration timeout = budget.cap(Duration.ofMillis(Math.max(100L, properties.getContentSnapshotTimeoutMs())));
        if (timeout.isZero() || timeout.isNegative()) {
            throw ContentSnapshotException.retryable(0, "BUDGET_EXHAUSTED",
                    "Timeout budget exhausted before content snapshot call", null, null);
        }
        return timeout;
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ContentSnapshotException.retryable(0, "INTERRUPTED",
                    "Content snapshot " + operation + " interrupted", null, interrupted);
        } catch (IOException ioEx) {
            throw ContentSnapshotException.retryable(0, "IO_FAILURE",
                    "Content snapshot " + operation + " IO failure: " + ioEx.getClass().getSimpleName(), null, ioEx);
        }
    }

    private void validateHttpStatus(HttpResponse<String> response, String operation) {
        int status = response.statusCode();
        if (status == 200) {
            return;
        }
        String errorCode = extractErrorCode(response.body());
        if (status == 410) {
            throw ContentSnapshotException.expired410(
                    errorCode != null ? errorCode : "SNAPSHOT_OR_CURSOR_EXPIRED",
                    "Content returned HTTP 410 on " + operation);
        }
        if (status == 401) {
            serviceTokenClient.invalidate(CONTENT_AUDIENCE, SNAPSHOT_PERMISSIONS);
            throw ContentSnapshotException.retryable(401,
                    errorCode != null ? errorCode : "INVALID_SERVICE_TOKEN",
                    "Content rejected snapshot service token with HTTP 401", null, null);
        }
        if (status == 429 || status >= 500) {
            Long retryAfter = parseRetryAfter(response);
            throw ContentSnapshotException.retryable(status,
                    errorCode != null ? errorCode : ("HTTP_" + status),
                    "Content transient HTTP " + status + " on " + operation, retryAfter, null);
        }
        throw ContentSnapshotException.nonRetryable(status,
                errorCode != null ? errorCode : ("HTTP_" + status),
                "Content non-retryable HTTP " + status + " on " + operation);
    }

    private SnapshotHandle parseSnapshotHandle(String body) {
        JsonNode root = parseStrictObject(body, "create_snapshot");
        requireOnlyAllowedFields(root, CREATE_FIELDS, "create_snapshot");
        String snapshotId = requireNonBlankText(root.get("snapshotId"), "snapshotId");
        String incrementalCursor = requireNonBlankText(root.get("incrementalCursor"), "incrementalCursor");
        Instant expiresAt = requireIsoInstant(root.get("expiresAt"), "expiresAt");
        return new SnapshotHandle(snapshotId, incrementalCursor, expiresAt);
    }

    private SnapshotPage parseSnapshotPage(String body, String expectedSnapshotId) {
        JsonNode root = parseStrictObject(body, "snapshot_page");
        requireOnlyAllowedFields(root, PAGE_FIELDS, "snapshot_page");

        String snapshotId = requireNonBlankText(root.get("snapshotId"), "snapshotId");
        if (!expectedSnapshotId.equals(snapshotId)) {
            throw ContentSnapshotException.nonRetryable(200, "SNAPSHOT_ID_MISMATCH",
                    "Returned snapshotId does not match requested handle");
        }
        JsonNode doneNode = root.get("done");
        if (doneNode == null || !doneNode.isBoolean()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_PAGE", "Missing boolean done");
        }
        boolean done = doneNode.booleanValue();

        JsonNode nextCursorNode = root.get("nextCursor");
        String nextCursor = null;
        if (nextCursorNode != null && !nextCursorNode.isNull()) {
            nextCursor = requireNonBlankText(nextCursorNode, "nextCursor");
        }
        if (done && nextCursor != null) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_PAGE",
                    "Snapshot page with done=true must have nextCursor=null");
        }
        if (!done && nextCursor == null) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_PAGE",
                    "Snapshot page with done=false must carry non-null nextCursor");
        }

        String incrementalCursor = requireNonBlankText(root.get("incrementalCursor"), "incrementalCursor");
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_PAGE", "Missing items array");
        }
        List<SnapshotArticleItem> items = new ArrayList<>(itemsNode.size());
        for (JsonNode itemNode : itemsNode) {
            items.add(parseSnapshotItem(itemNode));
        }
        return new SnapshotPage(snapshotId, List.copyOf(items), nextCursor, done, incrementalCursor);
    }

    private SnapshotArticleItem parseSnapshotItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Snapshot item must be object");
        }
        requireOnlyAllowedFields(node, SNAPSHOT_ITEM_FIELDS, "snapshot_item");
        long articleId = requirePositiveLong(node.get("articleId"), "articleId");
        long sourceRevision = requirePositiveLong(node.get("sourceRevision"), "sourceRevision");
        String publicationFactId = nullableText(node.get("publicationFactId"), "publicationFactId");
        long authorId = requirePositiveLong(node.get("authorId"), "authorId");
        String title = requireNonBlankText(node.get("title"), "title");
        String summary = nullableText(node.get("summary"), "summary");
        JsonNode contentNode = node.get("content");
        if (contentNode == null || !contentNode.isTextual()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Missing textual content");
        }
        String content = contentNode.asText();
        String thumbnail = nullableText(node.get("thumbnail"), "thumbnail");
        String isTop = nullableText(node.get("isTop"), "isTop");
        String isComment = nullableText(node.get("isComment"), "isComment");

        Long categoryId = null;
        String categoryName = null;
        JsonNode categoryNode = node.get("category");
        if (categoryNode != null && !categoryNode.isNull()) {
            if (!categoryNode.isObject()) {
                throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Invalid category object");
            }
            requireOnlyAllowedFields(categoryNode, ID_NAME_FIELDS, "category");
            categoryId = requirePositiveLong(categoryNode.get("id"), "category.id");
            categoryName = requireNonBlankText(categoryNode.get("name"), "category.name");
        }

        List<String> tagNames = new ArrayList<>();
        JsonNode tagsNode = node.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isArray()) {
                throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Invalid tags array");
            }
            Set<Long> seenTagIds = new HashSet<>();
            for (JsonNode tag : tagsNode) {
                if (tag == null || !tag.isObject()) {
                    throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Invalid tag object");
                }
                requireOnlyAllowedFields(tag, ID_NAME_FIELDS, "tag");
                long tagId = requirePositiveLong(tag.get("id"), "tag.id");
                String tagName = requireNonBlankText(tag.get("name"), "tag.name");
                if (!seenTagIds.add(tagId)) {
                    throw ContentSnapshotException.nonRetryable(200, "INVALID_SNAPSHOT_ITEM", "Duplicate tag id");
                }
                tagNames.add(tagName);
            }
        }

        Instant createTime = requireIsoInstant(node.get("createTime"), "createTime");
        Long publishedAtMs = null;
        JsonNode publishedAtNode = node.get("publishedAt");
        if (publishedAtNode != null && !publishedAtNode.isNull()) {
            publishedAtMs = requireIsoInstant(publishedAtNode, "publishedAt").toEpochMilli();
        }
        long viewCount = requireNonNegativeLong(node.get("viewCount"), "viewCount");
        long counterRevision = requireNonNegativeLong(node.get("counterRevision"), "counterRevision");

        return new SnapshotArticleItem(
                articleId,
                sourceRevision,
                publicationFactId,
                authorId,
                title,
                summary,
                content,
                thumbnail,
                isTop,
                isComment,
                categoryId,
                categoryName,
                List.copyOf(tagNames),
                createTime.toEpochMilli(),
                publishedAtMs,
                viewCount,
                counterRevision
        );
    }

    private ChangeFeedPage parseChangeFeedPage(String body) {
        JsonNode root = parseStrictObject(body, "article_changes");
        requireOnlyAllowedFields(root, CHANGES_FIELDS, "article_changes");

        String nextCursor = requireNonBlankText(root.get("nextCursor"), "nextCursor");
        JsonNode doneNode = root.get("done");
        if (doneNode == null || !doneNode.isBoolean()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_CHANGES_PAGE", "Missing boolean done");
        }
        boolean done = doneNode.booleanValue();
        Instant cursorExpiresAt = requireIsoInstant(root.get("cursorExpiresAt"), "cursorExpiresAt");

        JsonNode commitsNode = root.get("commits");
        if (commitsNode == null || !commitsNode.isArray()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_CHANGES_PAGE", "Missing commits array");
        }
        List<ChangeCommitBatch> commits = new ArrayList<>(commitsNode.size());
        long previousSeq = -1L;
        for (JsonNode commitNode : commitsNode) {
            if (commitNode == null || !commitNode.isObject()) {
                throw ContentSnapshotException.nonRetryable(200, "INVALID_CHANGES_COMMIT", "Commit entry must be object");
            }
            requireOnlyAllowedFields(commitNode, COMMIT_FIELDS, "commit");
            long seq = requirePositiveLong(commitNode.get("commitSequence"), "commitSequence");
            if (seq <= previousSeq) {
                throw ContentSnapshotException.nonRetryable(200, "NON_MONOTONIC_COMMIT_SEQ",
                        "Commit sequences must be strictly increasing");
            }
            previousSeq = seq;

            JsonNode eventsNode = commitNode.get("events");
            if (eventsNode == null || !eventsNode.isArray() || eventsNode.isEmpty()) {
                throw ContentSnapshotException.nonRetryable(200, "INVALID_CHANGES_COMMIT",
                        "Commit batch must have non-empty events array");
            }
            List<String> rawEvents = new ArrayList<>(eventsNode.size());
            for (JsonNode evt : eventsNode) {
                if (evt == null || !evt.isObject()) {
                    throw ContentSnapshotException.nonRetryable(200, "INVALID_CHANGES_EVENT",
                            "Change event must be JSON object");
                }
                try {
                    rawEvents.add(strictMapper.writeValueAsString(evt));
                } catch (IOException ex) {
                    throw ContentSnapshotException.nonRetryable(200, "EVENT_SERIALIZATION_ERROR",
                            "Failed to serialize embedded change event");
                }
            }
            commits.add(new ChangeCommitBatch(seq, List.copyOf(rawEvents)));
        }
        return new ChangeFeedPage(List.copyOf(commits), nextCursor, done, cursorExpiresAt);
    }

    private JsonNode parseStrictObject(String body, String context) {
        if (!StringUtils.hasText(body)) {
            throw ContentSnapshotException.retryable(200, "EMPTY_RESPONSE",
                    "Empty response body for " + context, null, null);
        }
        try {
            JsonNode root = strictMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw ContentSnapshotException.nonRetryable(200, "INVALID_JSON_OBJECT",
                        "Response root must be JSON object for " + context);
            }
            return root;
        } catch (ContentSnapshotException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_JSON",
                    "Malformed JSON for " + context);
        }
    }

    private static void requireOnlyAllowedFields(JsonNode node, Set<String> allowed, String context) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            if (!allowed.contains(field)) {
                throw ContentSnapshotException.nonRetryable(200, "UNEXPECTED_FIELD",
                        "Unexpected field '" + field + "' in " + context);
            }
        }
    }

    private static String requireNonBlankText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", "Missing or blank " + field);
        }
        return node.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", field + " must be string or null");
        }
        return node.asText();
    }

    private static long requirePositiveLong(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", field + " must be canonical decimal string");
        }
        String normalized = CanonicalIdParser.normalizePositiveDecimalString(node.asText());
        if (normalized == null) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", "Invalid positive decimal " + field);
        }
        return Long.parseLong(normalized);
    }

    private static long requireNonNegativeLong(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", field + " must be decimal string");
        }
        String raw = node.asText();
        if ("0".equals(raw)) {
            return 0L;
        }
        String normalized = CanonicalIdParser.normalizePositiveDecimalString(raw);
        if (normalized == null) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_FIELD", "Invalid non-negative decimal " + field);
        }
        return Long.parseLong(normalized);
    }

    private static Instant requireIsoInstant(JsonNode node, String field) {
        String text = requireNonBlankText(node, field);
        try {
            return Instant.parse(text);
        } catch (Exception ex) {
            throw ContentSnapshotException.nonRetryable(200, "INVALID_TIMESTAMP", "Invalid ISO-8601 " + field);
        }
    }

    private String extractErrorCode(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = strictMapper.readTree(body);
            JsonNode codeNode = root.path("error").path("code");
            return codeNode.isTextual() && !codeNode.asText().isBlank() ? codeNode.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long parseRetryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(raw -> {
                    try {
                        long v = Long.parseLong(raw.trim());
                        return v > 0 ? v : null;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
