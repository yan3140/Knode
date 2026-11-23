package com.knode.content.snapshot;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;

import static com.knode.content.snapshot.SnapshotFailure.Code.INVALID_ARGUMENT;

final class SnapshotRequests {
    private static final int MAX_REQUEST_BYTES = 8_192;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_REQUEST_BYTES)
                    .maxNestingDepth(4).maxStringLength(512).maxNumberLength(32).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    enum Purpose {
        PUBLIC_SEARCH, PUBLIC_KNOWLEDGE, SEARCH_REBUILD, KNOWLEDGE_REBUILD;
        boolean rebuild() {
            return this == SEARCH_REBUILD || this == KNOWLEDGE_REBUILD;
        }
    }

    record Visibility(List<Long> ids) {}
    record Create(String requestId, Purpose purpose) {}
    record Page(String cursor, int limit) {}

    private SnapshotRequests() {}

    static Visibility visibility(HttpServletRequest request) {
        JsonNode body = read(request, Set.of("articleIds", "purpose"));
        purpose(body.get("purpose"), false);
        return new Visibility(ids(body.get("articleIds")));
    }

    static List<Long> batch(HttpServletRequest request) {
        return ids(read(request, Set.of("articleIds")).get("articleIds"));
    }

    static Create create(HttpServletRequest request) {
        JsonNode body = read(request, Set.of("clientRequestId", "purpose"));
        String id = text(body.get("clientRequestId"));
        if (!id.matches("[A-Za-z0-9._-]{1,64}")) throw invalid();
        return new Create(id, purpose(body.get("purpose"), true));
    }

    static Page page(HttpServletRequest request, boolean cursorRequired) {
        Map<String, String[]> params = request.getParameterMap();
        if (!Set.of("cursor", "limit").containsAll(params.keySet())
                || params.values().stream().anyMatch(values -> values.length != 1)) throw invalid();
        String cursor = request.getParameter("cursor");
        if (cursorRequired || cursor != null) {
            if (cursor == null || cursor.isEmpty() || cursor.length() > SnapshotCursor.MAX_LENGTH) throw invalid();
        }
        String limit = request.getParameter("limit");
        if (limit == null) return new Page(cursor, SnapshotSettings.MAX_ITEMS);
        if (!limit.matches("[1-9][0-9]{0,2}") || Integer.parseInt(limit) > SnapshotSettings.MAX_ITEMS) throw invalid();
        return new Page(cursor, Integer.parseInt(limit));
    }

    static long decimal(String value, boolean zeroAllowed) {
        if (value == null || !value.matches(zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<Long> ids(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > SnapshotSettings.MAX_ITEMS) throw invalid();
        Set<Long> distinct = new LinkedHashSet<>();
        for (JsonNode id : value) distinct.add(decimal(text(id), false));
        return List.copyOf(distinct);
    }

    private static Purpose purpose(JsonNode value, boolean rebuild) {
        try {
            Purpose purpose = Purpose.valueOf(text(value));
            if (purpose.rebuild() != rebuild) throw invalid();
            return purpose;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static JsonNode read(HttpServletRequest request, Set<String> fields) {
        if (request.getQueryString() != null || request.getContentLengthLong() > MAX_REQUEST_BYTES) throw invalid();
        try {
            if (request.getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw invalid();
            JsonNode parsed = JSON.readTree(bytes);
            if (parsed == null || !parsed.isObject() || parsed.size() != fields.size()) throw invalid();
            for (String field : fields) if (!parsed.hasNonNull(field)) throw invalid();
            return parsed;
        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static SnapshotFailure invalid() {
        return new SnapshotFailure(INVALID_ARGUMENT);
    }
}
