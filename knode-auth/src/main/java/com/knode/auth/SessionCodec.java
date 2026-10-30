package com.knode.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pure JSON conversion; adapters own all network I/O. */
public final class SessionCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private SessionCodec() {}
    public static Session decode(String json) {
        try {
            if (json == null || json.length() > 4096) throw new IllegalArgumentException();
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject() || n.size() != 6
                    || !n.path("schemaVersion").isIntegralNumber() || !n.path("schemaVersion").canConvertToInt()
                    || !n.path("sid").isTextual() || !n.path("userId").isTextual()
                    || !n.path("scope").isTextual() || !n.path("status").isTextual()
                    || !n.path("expiresAt").isIntegralNumber() || !n.path("expiresAt").canConvertToLong())
                throw new IllegalArgumentException();
            return new Session(n.get("schemaVersion").intValue(), n.get("sid").textValue(), n.get("userId").textValue(),
                    n.get("scope").textValue(), n.get("status").textValue(), n.get("expiresAt").longValue());
        } catch (Exception e) { throw new AuthFailure(503); }
    }
    public static String encode(Session session) {
        try { return MAPPER.writeValueAsString(session); }
        catch (Exception e) { throw new AuthFailure(503); }
    }
}
