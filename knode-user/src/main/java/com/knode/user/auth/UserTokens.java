package com.knode.user.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.UserTokenVerifier;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Issuance requires prior first-party credential and account eligibility verification. */
public final class UserTokens {
    public static final long TTL = UserTokenVerifier.MAX_TTL_SECONDS;
    private final RSAPrivateKey privateKey;
    private final String signingKid;
    private final Clock clock;
    private final SessionStore.Reader reader;
    private final SessionStore.Writer writer;
    private final UserTokenVerifier verifier;
    public record Principal(String userId, String sid, String scope) {}
    public record Issued(String token,Principal principal,long expiresAt) {}
    public UserTokens(RSAPrivateKey privateKey, String signingKid, Map<String, RSAPublicKey> keys,
                      Clock clock, SessionStore.Reader reader, SessionStore.Writer writer) {
        this.privateKey = privateKey; this.signingKid = signingKid;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.reader = java.util.Objects.requireNonNull(reader); this.writer = writer;
        this.verifier = new UserTokenVerifier(keys,clock);
        if (privateKey != null && (signingKid == null || !keys.containsKey(signingKid)
                || !privateKey.getModulus().equals(keys.get(signingKid).getModulus()) || writer == null))
            throw new IllegalArgumentException("Signing key and session writer required");
    }
    public String issue(String authenticatedUserId, String scope) {
        return issueDetailed(authenticatedUserId,scope).token();
    }
    public Issued issueDetailed(String authenticatedUserId, String scope) {
        if (privateKey == null || writer == null) throw new IllegalStateException("Signing not configured");
        if (!UserTokenVerifier.validUserId(authenticatedUserId) || !UserTokenVerifier.validScope(scope))
            throw new IllegalArgumentException("Invalid principal");
        long now = clock.instant().getEpochSecond();
        String sid = UUID.randomUUID().toString();
        String token = Jwts.builder().header().keyId(signingKid).and()
                .issuer("knode-user").subject(authenticatedUserId).audience().add("knode-api").and()
                .claim("token_use", "user").claim("sid", sid).claim("scope", scope)
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(Instant.ofEpochSecond(now)))
                .expiration(Date.from(Instant.ofEpochSecond(now + TTL))).signWith(privateKey, Jwts.SIG.RS256).compact();
        try { writer.create(new SessionStore.Session(1,sid,authenticatedUserId,scope,"ACTIVE",now+TTL)); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        return new Issued(token,new Principal(authenticatedUserId,sid,scope),now+TTL);
    }
    public Principal authenticate(String token, String requiredScope) {
        if (!UserTokenVerifier.validScope(requiredScope)) throw new IllegalArgumentException("scope");
        return authenticateInternal(token,requiredScope);
    }
    public Principal authenticateAnyScope(String token) {
        return authenticateInternal(token,null);
    }
    private Principal authenticateInternal(String token,String requiredScope) {
        try {
            UserTokenVerifier.VerifiedToken verified = verifier.verifyToken(token);
            var state = reader.find(verified.sid()).map(s -> new Session(s.schemaVersion(),s.sid(),s.userId(),s.scope(),s.status(),s.expiresAt()));
            var p = verifier.validateSession(verified,state,requiredScope==null ? verified.scope() : requiredScope);
            return new Principal(p.userId(),p.sid(),p.scope());
        } catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        catch (AuthFailure e) { throw new AuthenticationFailure(e.status()); }
    }
    public void revoke(String token, String scope) {
        if (writer == null) throw new IllegalStateException("Session writer not configured");
        Principal principal = authenticate(token,scope);
        try { writer.revoke(principal.sid()); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
    }
    public <T> T authorized(String token, String scope, Supplier<T> handler) {
        authenticate(token,scope);
        return handler.get();
    }
}
