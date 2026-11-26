package com.knode.search.consumer.event;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.client.CanonicalIdParser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ContentEventParser {
    public static final int MAX_EVENT_UTF8_BYTES = 8_000_000;
    public static final int MAX_TITLE_CHARS = 256;
    public static final int MAX_SUMMARY_CHARS = 1_024;
    public static final int MAX_CONTENT_CHARS = 1_000_000;
    public static final int MAX_THUMBNAIL_CHARS = 2_048;
    public static final int MAX_TAGS = 50;
    public static final int MAX_COMMENT_PREVIEW_CODE_POINTS = 500;

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Set<String> ALLOWED_ENVELOPE_FIELDS = Set.of(
            "eventId", "eventType", "schemaVersion", "aggregateType", "aggregateId",
            "sourceRevision", "publicationFactId", "occurredAt", "producer",
            "visibility", "correlationId", "traceContext", "payload"
    );
    private static final Set<String> ALLOWED_PUBLIC_ARTICLE_FIELDS = Set.of(
            "articleId", "authorId", "title", "summary", "content", "thumbnail",
            "isTop", "isComment", "category", "tags", "createTime", "publishedAt",
            "viewCount", "counterRevision"
    );
    private static final Set<String> ALLOWED_HIDDEN_ARTICLE_FIELDS = Set.of(
            "articleId", "visibility", "sourceRevision", "occurredAt", "reason",
            "authorId", "deletedAt", "withdrawnAt"
    );
    private static final Set<String> FORBIDDEN_HIDDEN_BODY_FIELDS = Set.of(
            "content", "title", "summary", "thumbnail", "tags", "category"
    );
    private static final Set<String> ALLOWED_COUNTER_FIELDS = Set.of(
            "articleId", "viewCount", "counterRevision"
    );
    private static final Set<String> ALLOWED_COMMENT_FIELDS = Set.of(
            "commentId", "articleId", "rootCommentId", "toCommentId",
            "actorId", "receiverId", "commentType", "contentPreview"
    );
    private static final Set<String> ALLOWED_CATEGORY_OR_TAG_FIELDS = Set.of("id", "name");

    private final ObjectMapper strictMapper;

    public ContentEventParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_EVENT_UTF8_BYTES)
                        .maxStringLength(2_000_000)
                        .maxNestingDepth(32)
                        .build())
                .build();
        this.strictMapper = new ObjectMapper(factory);
    }

    public ParseResult parseStreamFields(Map<String, String> streamFields) {
        if (streamFields == null || streamFields.isEmpty() || !streamFields.containsKey("event")) {
            return ParseResult.fail(failure(null, "MISSING_EVENT_FIELD",
                    "Stream record must contain single 'event' field", new byte[0]));
        }
        if (streamFields.size() != 1) {
            return ParseResult.fail(failure(null, "UNEXPECTED_STREAM_FIELDS",
                    "Stream record must contain exactly one field named 'event'", new byte[0]));
        }
        String rawJson = streamFields.get("event");
        if (rawJson == null || rawJson.isBlank()) {
            return ParseResult.fail(failure(null, "MISSING_EVENT_FIELD",
                    "Stream record 'event' field is empty", new byte[0]));
        }
        return parseJson(rawJson);
    }

    public ParseResult parseJson(String rawJson) {
        if (rawJson == null) {
            return ParseResult.fail(failure(null, "MISSING_EVENT_FIELD", "Event JSON is null", new byte[0]));
        }
        byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EVENT_UTF8_BYTES) {
            return ParseResult.fail(failure(null, "PAYLOAD_TOO_LARGE",
                    "Event exceeds 8,000,000 UTF-8 bytes", bytes));
        }

        JsonNode root;
        try {
            root = strictMapper.readTree(rawJson);
        } catch (Exception ex) {
            return ParseResult.fail(failure(null, "INVALID_JSON",
                    "Malformed JSON or duplicate JSON fields", bytes));
        }
        if (root == null || !root.isObject()) {
            return ParseResult.fail(failure(null, "INVALID_ENVELOPE",
                    "Event root must be a JSON object", bytes));
        }

        String validEventId = extractValidEventId(root.get("eventId"));
        if (validEventId == null) {
            return ParseResult.fail(failure(null, "INVALID_ENVELOPE",
                    "Missing or invalid eventId", bytes));
        }

        String unknownEnvelope = firstUnknownField(root, ALLOWED_ENVELOPE_FIELDS);
        if (unknownEnvelope != null) {
            return ParseResult.fail(failure(validEventId, "INVALID_ENVELOPE",
                    "Unknown envelope field: " + unknownEnvelope, bytes));
        }

        String eventType = nonBlankText(root.get("eventType"), 64);
        String aggregateType = nonBlankText(root.get("aggregateType"), 64);
        String producer = nonBlankText(root.get("producer"), 128);
        Integer schemaVersion = parseExactPositiveInt(root.get("schemaVersion"));
        Long aggregateId = parsePositiveLong(root.get("aggregateId"));
        Long sourceRevision = parsePositiveLong(root.get("sourceRevision"));
        Instant occurredAt = parseIsoInstant(root.get("occurredAt"));
        JsonNode payload = root.get("payload");

        if (eventType == null || aggregateType == null || producer == null
                || schemaVersion == null || aggregateId == null || sourceRevision == null
                || occurredAt == null || payload == null || !payload.isObject()) {
            return ParseResult.fail(failure(validEventId, "INVALID_ENVELOPE",
                    "Missing or invalid required envelope fields", bytes));
        }

        if (root.has("correlationId") && !root.get("correlationId").isNull()) {
            if (!root.get("correlationId").isTextual() || root.get("correlationId").asText().length() > 128) {
                return ParseResult.fail(failure(validEventId, "INVALID_ENVELOPE",
                        "Invalid correlationId", bytes));
            }
        }
        if (root.has("traceContext") && !root.get("traceContext").isNull()) {
            if (!isValidTraceContext(root.get("traceContext"))) {
                return ParseResult.fail(failure(validEventId, "INVALID_ENVELOPE",
                        "Invalid traceContext", bytes));
            }
        }

        return switch (aggregateType) {
            case "ARTICLE" -> parseArticleEvent(
                    validEventId, eventType, schemaVersion, aggregateId, sourceRevision,
                    root.get("visibility"), root.get("publicationFactId"), payload, bytes);
            case "ARTICLE_COUNTER" -> parseCounterEvent(
                    validEventId, eventType, schemaVersion, aggregateId, sourceRevision,
                    root.get("visibility"), payload, bytes);
            case "COMMENT" -> parseCommentEvent(
                    validEventId, eventType, schemaVersion, aggregateId, sourceRevision,
                    root.get("visibility"), payload, bytes);
            default -> ParseResult.fail(failure(validEventId, "UNSUPPORTED_EVENT_TYPE",
                    "Unsupported aggregateType: " + aggregateType, bytes));
        };
    }

    private ParseResult parseArticleEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long aggregateId,
            long sourceRevision,
            JsonNode visibilityNode,
            JsonNode publicationFactIdNode,
            JsonNode payload,
            byte[] rawBytes) {
        if (schemaVersion != 1 && schemaVersion != 2) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_SCHEMA_VERSION",
                    "Unsupported ARTICLE schemaVersion: " + schemaVersion, rawBytes));
        }
        ArticleVisibility visibility = visibilityNode != null && visibilityNode.isTextual()
                ? ArticleVisibility.fromEventValue(visibilityNode.asText())
                : null;
        if (visibility == null) {
            return ParseResult.fail(failure(eventId, "INVALID_ENVELOPE",
                    "ARTICLE event requires visibility PUBLIC, WITHDRAWN or DELETED", rawBytes));
        }

        boolean validTypeVisibility = switch (eventType) {
            case "ArticlePublished", "ArticleContentChanged" -> visibility == ArticleVisibility.PUBLIC;
            case "ArticleVisibilityChanged" ->
                    visibility == ArticleVisibility.PUBLIC || visibility == ArticleVisibility.WITHDRAWN;
            case "ArticleDeleted" -> visibility == ArticleVisibility.DELETED;
            default -> false;
        };
        if (!validTypeVisibility) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_EVENT_TYPE",
                    "Invalid ARTICLE eventType/visibility combination", rawBytes));
        }

        if (visibility == ArticleVisibility.PUBLIC) {
            return parsePublicArticlePayload(
                    eventId, eventType, schemaVersion, aggregateId, sourceRevision,
                    publicationFactIdNode, payload, rawBytes);
        }
        return parseHiddenArticlePayload(
                eventId, eventType, schemaVersion, aggregateId, sourceRevision,
                visibility, payload, rawBytes);
    }

    private ParseResult parsePublicArticlePayload(
            String eventId,
            String eventType,
            int schemaVersion,
            long aggregateId,
            long sourceRevision,
            JsonNode publicationFactIdNode,
            JsonNode payload,
            byte[] rawBytes) {
        String unknown = firstUnknownField(payload, ALLOWED_PUBLIC_ARTICLE_FIELDS);
        if (unknown != null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Unknown public article payload field: " + unknown, rawBytes));
        }

        Long articleId = parsePositiveLong(payload.get("articleId"));
        Long authorId = parsePositiveLong(payload.get("authorId"));
        if (articleId == null || articleId != aggregateId || authorId == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid or mismatched articleId/authorId", rawBytes));
        }

        String title = nonBlankText(payload.get("title"), MAX_TITLE_CHARS);
        if (title == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Missing or oversized title", rawBytes));
        }

        String summary = nullableBoundedText(payload.get("summary"), MAX_SUMMARY_CHARS);
        if (payload.has("summary") && !payload.get("summary").isNull() && summary == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid or oversized summary", rawBytes));
        }

        JsonNode contentNode = payload.get("content");
        if (contentNode == null || !contentNode.isTextual() || contentNode.asText().length() > MAX_CONTENT_CHARS) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Missing or oversized content", rawBytes));
        }
        String content = contentNode.asText();

        String thumbnail = nullableBoundedText(payload.get("thumbnail"), MAX_THUMBNAIL_CHARS);
        if (payload.has("thumbnail") && !payload.get("thumbnail").isNull() && thumbnail == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid or oversized thumbnail", rawBytes));
        }

        String isTop = null;
        JsonNode isTopNode = payload.get("isTop");
        if (schemaVersion == 2) {
            if (isTopNode == null || !isTopNode.isTextual()
                    || (!"0".equals(isTopNode.asText()) && !"1".equals(isTopNode.asText()))) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Article v2 requires string isTop '0' or '1'", rawBytes));
            }
            isTop = isTopNode.asText();
        } else if (isTopNode != null && !isTopNode.isNull()) {
            if (!isTopNode.isTextual() || (!"0".equals(isTopNode.asText()) && !"1".equals(isTopNode.asText()))) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Invalid isTop value", rawBytes));
            }
            isTop = isTopNode.asText();
        }

        JsonNode isCommentNode = payload.get("isComment");
        if (isCommentNode != null && !isCommentNode.isNull()) {
            if (!isCommentNode.isTextual()
                    || (!"0".equals(isCommentNode.asText()) && !"1".equals(isCommentNode.asText()))) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Invalid isComment value", rawBytes));
            }
        }

        Long categoryId = null;
        String categoryName = null;
        JsonNode categoryNode = payload.get("category");
        if (categoryNode != null && !categoryNode.isNull()) {
            if (!categoryNode.isObject() || firstUnknownField(categoryNode, ALLOWED_CATEGORY_OR_TAG_FIELDS) != null) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Invalid category structure", rawBytes));
            }
            categoryId = parsePositiveLong(categoryNode.get("id"));
            categoryName = nonBlankText(categoryNode.get("name"), 256);
            if (categoryId == null || categoryName == null) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Invalid category id or name", rawBytes));
            }
        }

        List<String> tagNames = new ArrayList<>();
        JsonNode tagsNode = payload.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isArray() || tagsNode.size() > MAX_TAGS) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Invalid or oversized tags array", rawBytes));
            }
            Set<Long> seenTagIds = new HashSet<>();
            for (JsonNode tag : tagsNode) {
                if (tag == null || !tag.isObject() || firstUnknownField(tag, ALLOWED_CATEGORY_OR_TAG_FIELDS) != null) {
                    return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                            "Invalid tag object", rawBytes));
                }
                Long tagId = parsePositiveLong(tag.get("id"));
                String tagName = nonBlankText(tag.get("name"), 128);
                if (tagId == null || tagName == null || !seenTagIds.add(tagId)) {
                    return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                            "Invalid or duplicate tag id/name", rawBytes));
                }
                tagNames.add(tagName);
            }
        }

        Instant createTime = parseIsoInstant(payload.get("createTime"));
        if (createTime == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Missing or invalid createTime", rawBytes));
        }

        boolean allowNullFirstPub = schemaVersion == 2 && !"ArticlePublished".equals(eventType);
        JsonNode publishedAtNode = payload.get("publishedAt");
        if (publishedAtNode == null || publishedAtNode.isNull()) {
            if (!allowNullFirstPub) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Missing required publishedAt", rawBytes));
            }
        } else if (parseIsoInstant(publishedAtNode) == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid publishedAt timestamp", rawBytes));
        }

        if (publicationFactIdNode == null || publicationFactIdNode.isNull()) {
            if (!allowNullFirstPub) {
                return ParseResult.fail(failure(eventId, "INVALID_ENVELOPE",
                        "Missing required publicationFactId", rawBytes));
            }
        } else if (nonBlankText(publicationFactIdNode, 128) == null) {
            return ParseResult.fail(failure(eventId, "INVALID_ENVELOPE",
                    "Invalid publicationFactId", rawBytes));
        }

        Long viewCount = parseNonNegativeLong(payload.get("viewCount"));
        Long counterRevision = parseNonNegativeLong(payload.get("counterRevision"));
        if (viewCount == null || counterRevision == null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Missing or invalid viewCount/counterRevision", rawBytes));
        }

        return ParseResult.ok(new ParsedContentEvent.PublicArticleEvent(
                eventId,
                eventType,
                schemaVersion,
                articleId,
                sourceRevision,
                title,
                summary,
                content,
                categoryId,
                categoryName,
                List.copyOf(tagNames),
                thumbnail,
                isTop,
                createTime.toEpochMilli(),
                viewCount,
                counterRevision
        ));
    }

    private ParseResult parseHiddenArticlePayload(
            String eventId,
            String eventType,
            int schemaVersion,
            long aggregateId,
            long sourceRevision,
            ArticleVisibility visibility,
            JsonNode payload,
            byte[] rawBytes) {
        Iterator<String> fieldNames = payload.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (FORBIDDEN_HIDDEN_BODY_FIELDS.contains(field)) {
                return ParseResult.fail(failure(eventId, "FORBIDDEN_BODY_ON_HIDDEN_EVENT",
                        "Hidden/tombstone event must not carry body field: " + field, rawBytes));
            }
            if (!ALLOWED_HIDDEN_ARTICLE_FIELDS.contains(field)) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Unknown hidden article payload field: " + field, rawBytes));
            }
        }
        if (payload.has("reason") && !payload.get("reason").isNull() && !payload.get("reason").isTextual()) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Hidden article reason must be a scalar string or null", rawBytes));
        }
        if (payload.has("articleId") && !payload.get("articleId").isNull()) {
            Long payloadArticleId = parsePositiveLong(payload.get("articleId"));
            if (payloadArticleId == null || payloadArticleId != aggregateId) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Mismatched hidden articleId", rawBytes));
            }
        }
        if (payload.has("sourceRevision") && !payload.get("sourceRevision").isNull()) {
            Long payloadRev = parsePositiveLong(payload.get("sourceRevision"));
            if (payloadRev == null || payloadRev != sourceRevision) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Mismatched hidden sourceRevision", rawBytes));
            }
        }
        if (payload.has("visibility") && !payload.get("visibility").isNull()) {
            ArticleVisibility payloadVis = payload.get("visibility").isTextual()
                    ? ArticleVisibility.fromEventValue(payload.get("visibility").asText())
                    : null;
            if (payloadVis != visibility) {
                return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                        "Mismatched hidden visibility", rawBytes));
            }
        }

        return ParseResult.ok(new ParsedContentEvent.HiddenArticleEvent(
                eventId,
                eventType,
                schemaVersion,
                aggregateId,
                sourceRevision,
                visibility
        ));
    }

    private ParseResult parseCounterEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long aggregateId,
            long sourceRevision,
            JsonNode visibilityNode,
            JsonNode payload,
            byte[] rawBytes) {
        if (!"ArticleCounterChanged".equals(eventType)) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_EVENT_TYPE",
                    "Unsupported ARTICLE_COUNTER eventType: " + eventType, rawBytes));
        }
        if (schemaVersion != 1) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_SCHEMA_VERSION",
                    "Unsupported ARTICLE_COUNTER schemaVersion: " + schemaVersion, rawBytes));
        }
        ArticleVisibility visibility = visibilityNode != null && visibilityNode.isTextual()
                ? ArticleVisibility.fromEventValue(visibilityNode.asText())
                : null;
        if (visibility == null) {
            return ParseResult.fail(failure(eventId, "INVALID_ENVELOPE",
                    "ARTICLE_COUNTER event requires visibility PUBLIC, WITHDRAWN or DELETED", rawBytes));
        }
        String unknown = firstUnknownField(payload, ALLOWED_COUNTER_FIELDS);
        if (unknown != null || payload.size() != 3) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "ArticleCounterChanged payload must contain exactly articleId, viewCount and counterRevision",
                    rawBytes));
        }
        Long articleId = parsePositiveLong(payload.get("articleId"));
        Long viewCount = parseNonNegativeLong(payload.get("viewCount"));
        Long counterRevision = parsePositiveLong(payload.get("counterRevision"));
        if (articleId == null || articleId != aggregateId
                || viewCount == null || counterRevision == null || counterRevision != sourceRevision) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid ArticleCounterChanged fields or revision mismatch", rawBytes));
        }
        return ParseResult.ok(new ParsedContentEvent.CounterChangedEvent(
                eventId,
                eventType,
                schemaVersion,
                articleId,
                viewCount,
                counterRevision,
                visibility
        ));
    }

    private ParseResult parseCommentEvent(
            String eventId,
            String eventType,
            int schemaVersion,
            long aggregateId,
            long sourceRevision,
            JsonNode visibilityNode,
            JsonNode payload,
            byte[] rawBytes) {
        if (!"CommentCreated".equals(eventType) && !"CommentReplied".equals(eventType)) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_EVENT_TYPE",
                    "Unsupported COMMENT eventType: " + eventType, rawBytes));
        }
        if (schemaVersion != 1) {
            return ParseResult.fail(failure(eventId, "UNSUPPORTED_SCHEMA_VERSION",
                    "Unsupported COMMENT schemaVersion: " + schemaVersion, rawBytes));
        }
        String unknown = firstUnknownField(payload, ALLOWED_COMMENT_FIELDS);
        if (unknown != null) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Unknown comment payload field: " + unknown, rawBytes));
        }
        Long commentId = parsePositiveLong(payload.get("commentId"));
        if (commentId == null || commentId != aggregateId) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Invalid or mismatched commentId", rawBytes));
        }
        JsonNode previewNode = payload.get("contentPreview");
        if (previewNode == null || !previewNode.isTextual()) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Comment event missing contentPreview", rawBytes));
        }
        String preview = previewNode.asText();
        if (preview.codePointCount(0, preview.length()) > MAX_COMMENT_PREVIEW_CODE_POINTS) {
            return ParseResult.fail(failure(eventId, "INVALID_PAYLOAD",
                    "Comment contentPreview exceeds 500 code points", rawBytes));
        }
        String visRaw = visibilityNode != null && visibilityNode.isTextual() ? visibilityNode.asText() : null;
        return ParseResult.ok(new ParsedContentEvent.IgnoredNonSearchEvent(
                eventId,
                eventType,
                schemaVersion,
                "COMMENT",
                Long.toString(aggregateId),
                sourceRevision,
                visRaw
        ));
    }

    private static boolean isValidTraceContext(JsonNode node) {
        if (!node.isObject() || node.size() != 1 || !node.has("traceparent")) {
            return false;
        }
        JsonNode tp = node.get("traceparent");
        return tp.isTextual() && tp.asText().length() <= 128 && !tp.asText().isBlank();
    }

    private static String extractValidEventId(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String candidate = node.asText().trim();
        return EVENT_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static String firstUnknownField(JsonNode objectNode, Set<String> allowed) {
        Iterator<String> names = objectNode.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                return field;
            }
        }
        return null;
    }

    private static String nonBlankText(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        if (value.isBlank() || value.length() > maxLength) {
            return null;
        }
        return value;
    }

    private static String nullableBoundedText(JsonNode node, int maxLength) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().length() > maxLength) {
            return null;
        }
        return node.asText();
    }

    private static Integer parseExactPositiveInt(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        int value = node.intValue();
        return value > 0 ? value : null;
    }

    private static Long parsePositiveLong(JsonNode node) {
        String normalized = CanonicalIdParser.parseCanonicalPositiveFromJson(node);
        return normalized == null ? null : Long.parseLong(normalized);
    }

    private static Long parseNonNegativeLong(JsonNode node) {
        if (node == null || node.isNull() || node.isBoolean() || node.isFloatingPointNumber()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                return null;
            }
            long value = node.longValue();
            return value >= 0L ? value : null;
        }
        if (node.isTextual()) {
            String raw = node.asText();
            if (raw.isEmpty() || raw.length() > 19) {
                return null;
            }
            if ("0".equals(raw)) {
                return 0L;
            }
            String positive = CanonicalIdParser.normalizePositiveDecimalString(raw);
            return positive == null ? null : Long.parseLong(positive);
        }
        return null;
    }

    private static Instant parseIsoInstant(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ProtocolFailure failure(String eventId, String code, String message, byte[] rawBytes) {
        String boundedMsg = message == null ? code : (message.length() > 500 ? message.substring(0, 500) : message);
        return new ProtocolFailure(eventId, code, boundedMsg, sha256Hex(rawBytes), rawBytes.length);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record ParseResult(ParsedContentEvent event, ProtocolFailure failure) {
        public static ParseResult ok(ParsedContentEvent event) {
            return new ParseResult(event, null);
        }

        public static ParseResult fail(ProtocolFailure failure) {
            return new ParseResult(null, failure);
        }

        public boolean isSuccess() {
            return event != null;
        }
    }
}
