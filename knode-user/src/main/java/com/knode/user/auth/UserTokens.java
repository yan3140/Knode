package com.knode.user.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Core only: caller must authenticate credentials and check account eligibility before issue. */
public final class UserTokens {
    public static final long TTL = 3600;
    private final RSAPrivateKey privateKey;
    private final String signingKid;
    private final Map<String, RSAPublicKey> keys;
    private final Clock clock;
    private final SessionStore.Reader reader;
    private final SessionStore.Writer writer;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Principal(String userId, String sid, String scope) {}

    public UserTokens(RSAPrivateKey privateKey, String signingKid, Map<String, RSAPublicKey> keys,
                      Clock clock, SessionStore.Reader reader, SessionStore.Writer writer) {
        this.privateKey = privateKey;
        this.signingKid = signingKid;
        this.keys = Map.copyOf(keys);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.reader = java.util.Objects.requireNonNull(reader);
        this.writer = writer;
        if (this.keys.isEmpty() || this.keys.values().stream().anyMatch(k -> k.getModulus().bitLength() < 2048))
            throw new IllegalArgumentException("RSA public keys must be at least 2048 bits");
        if (privateKey != null && (signingKid == null || !this.keys.containsKey(signingKid)
                || !privateKey.getModulus().equals(this.keys.get(signingKid).getModulus()) || writer == null))
            throw new IllegalArgumentException("Signing key and session writer required");
    }

    /** Only call after trusted first-party credential verification; no HTTP login endpoint exists. */
    public String issue(String authenticatedUserId, String scope) {
        if (privateKey == null || writer == null) throw new IllegalStateException("Signing not configured");
        if (!validUserId(authenticatedUserId) || !validScope(scope)) throw new IllegalArgumentException("Invalid principal");
        long now = clock.instant().getEpochSecond();
        String sid = UUID.randomUUID().toString();
        String token = Jwts.builder().header().keyId(signingKid).and()
                .issuer("knode-user").subject(authenticatedUserId).audience().add("knode-api").and()
                .claim("token_use", "user").claim("sid", sid).claim("scope", scope)
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(Instant.ofEpochSecond(now)))
                .expiration(Date.from(Instant.ofEpochSecond(now + TTL))).signWith(privateKey, Jwts.SIG.RS256).compact();
        try {
            writer.create(new SessionStore.Session(1, sid, authenticatedUserId, scope, "ACTIVE", now + TTL));
        } catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        return token;
    }

    public Principal authenticate(String token, String requiredScope) {
        if (!validScope(requiredScope)) throw new IllegalArgumentException("Invalid required scope");
        Claims claims;
        try {
            if (token == null || token.length() > 8192) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].length() > 2048) throw new IllegalArgumentException();
            JsonNode header = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (header == null || !header.isObject() || !header.path("alg").isTextual()
                    || !"RS256".equals(header.path("alg").textValue()) || !header.path("kid").isTextual())
                throw new IllegalArgumentException();
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (payload == null || !payload.isObject()
                    || !payload.path("iat").isIntegralNumber() || !payload.path("iat").canConvertToLong()
                    || !payload.path("exp").isIntegralNumber() || !payload.path("exp").canConvertToLong())
                throw new IllegalArgumentException();
            RSAPublicKey key = keys.get(header.path("kid").textValue());
            if (key == null) throw new IllegalArgumentException();
            claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            long now = clock.instant().getEpochSecond();
            Date issued = claims.getIssuedAt(), expires = claims.getExpiration();
            String user = claims.getSubject(), sid = claims.get("sid", String.class);
            String scope = claims.get("scope", String.class), jti = claims.getId();
            if (!"knode-user".equals(claims.getIssuer()) || !claims.getAudience().equals(java.util.Set.of("knode-api"))
                    || !"user".equals(claims.get("token_use", String.class)) || !validUserId(user)
                    || !validUuid(sid) || !validUuid(jti) || !requiredScope.equals(scope)
                    || issued == null || expires == null || issued.toInstant().getEpochSecond() > now
                    || expires.toInstant().getEpochSecond() <= now
                    || expires.toInstant().getEpochSecond() <= issued.toInstant().getEpochSecond()
                    || expires.toInstant().getEpochSecond() - issued.toInstant().getEpochSecond() > TTL)
                throw new IllegalArgumentException();
        } catch (Exception e) { throw new AuthenticationFailure(401); }
        String sid = claims.get("sid", String.class), user = claims.getSubject(), scope = claims.get("scope", String.class);
        SessionStore.Session state;
        try { state = reader.find(sid).orElseThrow(() -> new AuthenticationFailure(401)); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        if (state.schemaVersion() != 1 || !sid.equals(state.sid()) || !user.equals(state.userId())
                || !scope.equals(state.scope()) || !"ACTIVE".equals(state.status())
                || state.expiresAt() <= clock.instant().getEpochSecond()
                || claims.getExpiration().toInstant().getEpochSecond() > state.expiresAt())
            throw new AuthenticationFailure(401);
        return new Principal(user, sid, scope);
    }

    public void revoke(String token, String scope) {
        if (writer == null) throw new IllegalStateException("Session writer not configured");
        Principal principal = authenticate(token, scope);
        try { writer.revoke(principal.sid()); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
    }

    public <T> T authorized(String token, String scope, Supplier<T> handler) {
        authenticate(token, scope);
        return handler.get();
    }
    private static boolean validScope(String scope) { return "blog".equals(scope) || "admin".equals(scope); }
    private static boolean validUserId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    private static boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }
}
