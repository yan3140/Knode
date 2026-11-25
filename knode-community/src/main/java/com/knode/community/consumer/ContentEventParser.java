package com.knode.community.consumer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.community.support.OriginPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public final class ContentEventParser {
    public static final int MAX_EVENT_BYTES = 8_000_000;

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_EVENT_BYTES + 4096)
                    .maxStringLength(2_000_000)
                    .maxNestingDepth(64)
                    .maxNumberLength(32)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId", "eventType", "schemaVersion", "producer", "aggregateType", "aggregateId",
            "sourceRevision", "publicationFactId", "occurredAt", "visibility",
            "correlationId", "traceContext", "payload");

    public sealed interface ParsedOutcome permits ArticleFact, CommentFact, CounterFact, UnsupportedFact {}

    public record ArticleFact(
            String eventId,
            String eventType,
            int schemaVersion,
            long articleId,
            long sourceRevision,
            String visibility,
            String publicationFactId,
            Long authorId,
            String title,
            Instant publishedAt,
            String traceparent,
            String payloadSha256) implements ParsedOutcome {}

    public record CommentFact(
            String eventId,
            String eventType,
            int schemaVersion,
            long commentId,
            long articleId,
            long rootCommentId,
            long toCommentId,
            long actorId,
            long receiverId,
            String contentPreview,
            String traceparent,
            String payloadSha256) implements ParsedOutcome {}

    public record CounterFact(
            String eventId,
            int schemaVersion,
            long articleId,
            long counterRevision,
            String traceparent,
            String payloadSha256) implements ParsedOutcome {}

    public record UnsupportedFact(
            String syntheticEventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            String payloadSha256,
            String reason) implements ParsedOutcome {}

    private ContentEventParser() {}

    public static ParsedOutcome parse(String streamEntryId, String rawJson) {
        if (rawJson == null) {
            String hash = sha256("null:" + streamEntryId);
            return new UnsupportedFact(poisonUuid(hash), "MALFORMED", 0, "UNKNOWN", null, hash, "Missing event field");
        }
        byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);
        String hash = sha256Bytes(bytes);
        if (bytes.length == 0 || bytes.length > MAX_EVENT_BYTES) {
            return new UnsupportedFact(poisonUuid(hash), "OVERSIZED_OR_EMPTY", 0, "UNKNOWN", null, hash, "Event size out of bounds");
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (Exception malformed) {
            return new UnsupportedFact(poisonUuid(hash), "MALFORMED_JSON", 0, "UNKNOWN", null, hash, "Malformed JSON");
        }
        String rawEventId = text(root, "eventId");
        String fallbackId = canonicalUuid(rawEventId) ? rawEventId : poisonUuid(hash);
        try {
            if (!onlyAllowed(root, ENVELOPE_FIELDS)
                    || !canonicalUuid(rawEventId)
                    || !"knode-content".equals(text(root, "producer"))
                    || text(root, "eventType") == null
                    || text(root, "aggregateType") == null
                    || !OriginPolicy.validPositiveId(text(root, "aggregateId"))
                    || !OriginPolicy.validPositiveId(text(root, "sourceRevision"))
                    || text(root, "occurredAt") == null
                    || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("schemaVersion").canConvertToInt()) {
                return unsupported(root, fallbackId, hash, "Invalid event envelope");
            }
            Instant.parse(text(root, "occurredAt"));
            String traceparent = parseTraceparent(root);
            int version = root.get("schemaVersion").intValue();
            String aggregateType = text(root, "aggregateType");
            String eventType = text(root, "eventType");
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                return unsupported(root, fallbackId, hash, "Missing object payload");
            }
            return switch (aggregateType) {
                case "ARTICLE" -> parseArticle(root, payload, rawEventId, eventType, version, traceparent, hash);
                case "COMMENT" -> parseComment(root, payload, rawEventId, eventType, version, traceparent, hash);
                case "ARTICLE_COUNTER" -> parseCounter(root, payload, rawEventId, eventType, version, traceparent, hash);
                default -> unsupported(root, rawEventId, hash, "Unsupported aggregateType: " + aggregateType);
            };
        } catch (IllegalArgumentException invalid) {
            return unsupported(root, fallbackId, hash, invalid.getMessage());
        } catch (Exception invalid) {
            return unsupported(root, fallbackId, hash, "Event validation failed");
        }
    }

    private static ParsedOutcome parseArticle(
            JsonNode root, JsonNode payload, String eventId, String eventType,
            int version, String traceparent, String hash) {
        if (version != 1 && version != 2) {
            return unsupported(root, eventId, hash, "Unsupported article schemaVersion: " + version);
        }
        String visibility = text(root, "visibility");
        boolean isPublic = "PUBLIC".equals(visibility);
        boolean validCombo = (("ArticlePublished".equals(eventType) || "ArticleContentChanged".equals(eventType)) && isPublic)
                || ("ArticleVisibilityChanged".equals(eventType) && (isPublic || "WITHDRAWN".equals(visibility)))
                || ("ArticleDeleted".equals(eventType) && "DELETED".equals(visibility));
        if (!validCombo) {
            throw new IllegalArgumentException("Invalid article eventType/visibility combination");
        }
        String articleIdStr = text(payload, "articleId");
        if (!text(root, "aggregateId").equals(articleIdStr)) {
            throw new IllegalArgumentException("Mismatched articleId");
        }
        long articleId = Long.parseLong(articleIdStr);
        long sourceRevision = Long.parseLong(text(root, "sourceRevision"));

        String publicationFactId = null;
        if (root.has("publicationFactId") && !root.get("publicationFactId").isNull()) {
            publicationFactId = text(root, "publicationFactId");
            if (publicationFactId == null || !publicationFactId.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("Invalid publicationFactId");
            }
        }
        if (isPublic) {
            boolean requiresFirstPub = (version == 1) || "ArticlePublished".equals(eventType);
            String authorIdStr = text(payload, "authorId");
            String title = text(payload, "title");
            String pubAtStr = payload.has("publishedAt") && !payload.get("publishedAt").isNull()
                    ? text(payload, "publishedAt") : null;
            if (!OriginPolicy.validPositiveId(authorIdStr)
                    || title == null || title.isEmpty() || title.length() > 256) {
                throw new IllegalArgumentException("Invalid public article authorId or title");
            }
            if (requiresFirstPub && (publicationFactId == null || pubAtStr == null)) {
                throw new IllegalArgumentException("ArticlePublished requires publicationFactId and publishedAt");
            }
            Instant publishedAt = pubAtStr == null ? null : Instant.parse(pubAtStr);
            return new ArticleFact(
                    eventId, eventType, version, articleId, sourceRevision, visibility,
                    publicationFactId, Long.parseLong(authorIdStr), title, publishedAt, traceparent, hash);
        }
        return new ArticleFact(
                eventId, eventType, version, articleId, sourceRevision, visibility,
                publicationFactId, null, null, null, traceparent, hash);
    }

    private static ParsedOutcome parseComment(
            JsonNode root, JsonNode payload, String eventId, String eventType,
            int version, String traceparent, String hash) {
        if (version != 1) {
            return unsupported(root, eventId, hash, "Unsupported comment schemaVersion: " + version);
        }
        if (!Set.of("CommentCreated", "CommentReplied").contains(eventType)) {
            return unsupported(root, eventId, hash, "Unsupported comment eventType: " + eventType);
        }
        Set<String> allowed = Set.of(
                "commentId", "articleId", "rootCommentId", "toCommentId",
                "actorId", "receiverId", "commentType", "contentPreview");
        if (!OriginPolicy.exactFields(payload, allowed)) {
            throw new IllegalArgumentException("Invalid comment payload fields");
        }
        String commentIdStr = text(payload, "commentId");
        String articleIdStr = text(payload, "articleId");
        String rootIdStr = text(payload, "rootCommentId");
        String toIdStr = text(payload, "toCommentId");
        String actorIdStr = text(payload, "actorId");
        String receiverIdStr = text(payload, "receiverId");
        String commentType = text(payload, "commentType");
        String preview = text(payload, "contentPreview");
        if (!text(root, "aggregateId").equals(commentIdStr)
                || !"1".equals(text(root, "sourceRevision"))
                || !"0".equals(commentType)
                || !OriginPolicy.validPositiveId(commentIdStr)
                || !OriginPolicy.validPositiveId(articleIdStr)
                || !OriginPolicy.validPositiveId(actorIdStr)
                || !OriginPolicy.validPositiveId(receiverIdStr)
                || preview == null
                || preview.codePointCount(0, preview.length()) > 500) {
            throw new IllegalArgumentException("Invalid comment payload values");
        }
        boolean isRoot = "-1".equals(rootIdStr) && "-1".equals(toIdStr);
        boolean isReply = OriginPolicy.validPositiveId(rootIdStr) && OriginPolicy.validPositiveId(toIdStr);
        if (("CommentCreated".equals(eventType) && !isRoot) || ("CommentReplied".equals(eventType) && !isReply)) {
            throw new IllegalArgumentException("Mismatched comment thread pointers");
        }
        return new CommentFact(
                eventId, eventType, version,
                Long.parseLong(commentIdStr), Long.parseLong(articleIdStr),
                Long.parseLong(rootIdStr), Long.parseLong(toIdStr),
                Long.parseLong(actorIdStr), Long.parseLong(receiverIdStr),
                preview, traceparent, hash);
    }

    private static ParsedOutcome parseCounter(
            JsonNode root, JsonNode payload, String eventId, String eventType,
            int version, String traceparent, String hash) {
        if (version != 1 || !"ArticleCounterChanged".equals(eventType)) {
            return unsupported(root, eventId, hash, "Unsupported counter event");
        }
        String articleIdStr = text(payload, "articleId");
        String counterRevStr = text(payload, "counterRevision");
        String viewCountStr = text(payload, "viewCount");
        if (!OriginPolicy.exactFields(payload, Set.of("articleId", "viewCount", "counterRevision"))
                || !text(root, "aggregateId").equals(articleIdStr)
                || !nonNegativeInt64(viewCountStr)
                || !OriginPolicy.validPositiveId(counterRevStr)) {
            throw new IllegalArgumentException("Invalid counter payload");
        }
        return new CounterFact(eventId, version, Long.parseLong(articleIdStr), Long.parseLong(counterRevStr), traceparent, hash);
    }

    private static String parseTraceparent(JsonNode root) {
        if (!root.has("traceContext") || root.get("traceContext").isNull()) {
            return null;
        }
        JsonNode tc = root.get("traceContext");
        if (!OriginPolicy.exactFields(tc, Set.of("traceparent"))) {
            throw new IllegalArgumentException("Invalid traceContext structure");
        }
        String tp = text(tc, "traceparent");
        if (tp == null || tp.isBlank() || tp.length() > 128) {
            throw new IllegalArgumentException("Invalid traceparent");
        }
        return tp;
    }

    private static UnsupportedFact unsupported(JsonNode root, String id, String hash, String reason) {
        String type = bounded(text(root, "eventType"), 64, "UNSUPPORTED");
        int ver = root != null && root.path("schemaVersion").canConvertToInt() ? root.path("schemaVersion").intValue() : 0;
        String aggType = bounded(text(root, "aggregateType"), 32, "UNKNOWN");
        String aggId = bounded(text(root, "aggregateId"), 32, null);
        return new UnsupportedFact(id, type, ver, aggType, aggId, hash, bounded(reason, 480, "Unsupported event"));
    }

    private static String bounded(String s, int max, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean onlyAllowed(JsonNode obj, Set<String> allowed) {
        if (obj == null || !obj.isObject()) return false;
        Iterator<String> it = obj.fieldNames();
        while (it.hasNext()) {
            if (!allowed.contains(it.next())) return false;
        }
        return true;
    }

    private static String text(JsonNode obj, String key) {
        if (obj == null || !obj.isObject()) return null;
        JsonNode v = obj.get(key);
        return v != null && v.isTextual() ? v.textValue() : null;
    }

    private static boolean nonNegativeInt64(String s) {
        if (s == null || !s.matches("0|[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(s) >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean canonicalUuid(String s) {
        if (s == null || s.length() != 36) return false;
        try {
            return UUID.fromString(s).toString().equals(s);
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String poisonUuid(String hash) {
        return UUID.nameUUIDFromBytes(("POISON_EVENT:" + hash).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256(String s) {
        return sha256Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
