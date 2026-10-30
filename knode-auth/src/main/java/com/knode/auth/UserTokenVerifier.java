package com.knode.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** CPU-only verification; the caller performs authoritative session I/O between these methods. */
public final class UserTokenVerifier {
    public static final long MAX_TTL_SECONDS = 3600;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, RSAPublicKey> keys;
    private final Clock clock;
    public record Principal(String userId, String sid, String scope) {}
    public static final class VerifiedToken {
        private final String userId, sid, scope;
        private final long expiresAt;
        private VerifiedToken(String userId, String sid, String scope, long expiresAt) {
            this.userId = userId; this.sid = sid; this.scope = scope; this.expiresAt = expiresAt;
        }
        public String userId() { return userId; }
        public String sid() { return sid; }
        public String scope() { return scope; }
        public long expiresAt() { return expiresAt; }
    }
    public UserTokenVerifier(Map<String, RSAPublicKey> keys, Clock clock) {
        this.keys = Map.copyOf(keys); this.clock = java.util.Objects.requireNonNull(clock);
        if (this.keys.isEmpty() || this.keys.values().stream().anyMatch(k -> k.getModulus().bitLength() < 2048))
            throw new IllegalArgumentException("RSA public keys must be at least 2048 bits");
    }
    public VerifiedToken verifyToken(String token) {
        try {
            if (token == null || token.length() > 8192) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].length() > 2048) throw new IllegalArgumentException();
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (header == null || !header.isObject() || !header.path("alg").isTextual()
                    || !"RS256".equals(header.path("alg").textValue()) || !header.path("kid").isTextual())
                throw new IllegalArgumentException();
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (payload == null || !payload.isObject() || !payload.path("iat").isIntegralNumber()
                    || !payload.path("iat").canConvertToLong() || !payload.path("exp").isIntegralNumber()
                    || !payload.path("exp").canConvertToLong()) throw new IllegalArgumentException();
            RSAPublicKey key = keys.get(header.path("kid").textValue());
            if (key == null) throw new IllegalArgumentException();
            Claims c = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            long now = clock.instant().getEpochSecond();
            Date iat = c.getIssuedAt(), exp = c.getExpiration();
            String user = c.getSubject(), sid = c.get("sid",String.class), scope = c.get("scope",String.class);
            if (!"knode-user".equals(c.getIssuer()) || !Set.of("knode-api").equals(c.getAudience())
                    || !"user".equals(c.get("token_use",String.class)) || !validUserId(user)
                    || !validUuid(sid) || !validUuid(c.getId()) || !validScope(scope)
                    || iat == null || exp == null || iat.toInstant().getEpochSecond() > now
                    || exp.toInstant().getEpochSecond() <= now
                    || exp.toInstant().getEpochSecond() <= iat.toInstant().getEpochSecond()
                    || exp.toInstant().getEpochSecond() - iat.toInstant().getEpochSecond() > MAX_TTL_SECONDS)
                throw new IllegalArgumentException();
            return new VerifiedToken(user,sid,scope,exp.toInstant().getEpochSecond());
        } catch (Exception e) { throw new AuthFailure(401); }
    }
    public Principal validateSession(VerifiedToken token, Optional<Session> state, String requiredScope) {
        if (!validScope(requiredScope)) throw new IllegalArgumentException("scope");
        java.util.Objects.requireNonNull(token);
        Session s = state.orElseThrow(() -> new AuthFailure(401));
        long now = clock.instant().getEpochSecond();
        if (token.expiresAt <= now || s.schemaVersion() != 1 || !token.sid.equals(s.sid())
                || !token.userId.equals(s.userId()) || !token.scope.equals(s.scope()) || !"ACTIVE".equals(s.status())
                || s.expiresAt() <= now || token.expiresAt > s.expiresAt()) throw new AuthFailure(401);
        if (!requiredScope.equals(token.scope)) throw new AuthFailure(403);
        return new Principal(token.userId, token.sid, token.scope);
    }
    public static boolean validScope(String value) { return "blog".equals(value) || "admin".equals(value); }
    public static boolean validUserId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    public static boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }
}
