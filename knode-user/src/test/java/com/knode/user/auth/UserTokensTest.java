package com.knode.user.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class UserTokensTest {
    static KeyPair key, other;
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        other = generator.generateKeyPair();
    }
    static class Memory implements SessionStore.Reader, SessionStore.Writer {
        final Map<String, SessionStore.Session> sessions = new HashMap<>();
        boolean unavailable;
        public Optional<SessionStore.Session> find(String sid) {
            if (unavailable) throw new SessionStore.Unavailable("offline");
            return Optional.ofNullable(sessions.get(sid));
        }
        public void create(SessionStore.Session session) {
            if (unavailable) throw new SessionStore.Unavailable("offline");
            if (sessions.putIfAbsent(session.sid(), session) != null) throw new IllegalStateException("collision");
        }
        public void revoke(String sid) {
            if (unavailable) throw new SessionStore.Unavailable("offline");
            sessions.remove(sid);
        }
    }
    UserTokens core(Memory store, Clock clock) {
        return new UserTokens((RSAPrivateKey)key.getPrivate(), "key1",
                Map.of("key1",(RSAPublicKey)key.getPublic()), clock, store, store);
    }
    void denied(Runnable op, int status) {
        assertEquals(status, assertThrows(AuthenticationFailure.class, op::run).status());
    }

    @Test void revocationIsPerSidAndCannotReviveAfterNewLogin() {
        Memory store = new Memory();
        UserTokens auth = core(store, CLOCK);
        String a = auth.issue("42","blog");
        String b = auth.issue("42","blog");
        assertNotEquals(auth.authenticate(a,"blog").sid(),auth.authenticate(b,"blog").sid());
        auth.revoke(a,"blog");
        String c = auth.issue("42","blog");
        denied(() -> auth.authenticate(a,"blog"),401);
        assertEquals("42",auth.authenticate(b,"blog").userId());
        assertEquals("42",auth.authenticate(c,"blog").userId());
    }
    @Test void scopesAndSessionBindingCannotBeMixed() {
        Memory store = new Memory(); UserTokens auth = core(store,CLOCK);
        String blog=auth.issue("42","blog"), admin=auth.issue("42","admin");
        denied(() -> auth.authenticate(blog,"admin"),401);
        denied(() -> auth.authenticate(admin,"blog"),401);
        String sid=auth.authenticate(blog,"blog").sid();
        SessionStore.Session original=store.sessions.get(sid);
        for (SessionStore.Session bad : new SessionStore.Session[] {
                new SessionStore.Session(1,sid,"43","blog","ACTIVE",original.expiresAt()),
                new SessionStore.Session(1,sid,"42","admin","ACTIVE",original.expiresAt()),
                new SessionStore.Session(1,UUID.randomUUID().toString(),"42","blog","ACTIVE",original.expiresAt()),
                new SessionStore.Session(1,sid,"42","blog","REVOKED",original.expiresAt()),
                new SessionStore.Session(2,sid,"42","blog","ACTIVE",original.expiresAt()),
                new SessionStore.Session(1,sid,"42","blog","ACTIVE",NOW.getEpochSecond()) }) {
            store.sessions.put(sid,bad);
            denied(() -> auth.authenticate(blog,"blog"),401);
        }
    }
    String token(String sid, String audience, String use, String kid, KeyPair signer, Instant exp) {
        return Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject("42")
                .audience().add(audience).and().claim("token_use",use).claim("sid",sid)
                .claim("scope","blog").id(UUID.randomUUID().toString()).issuedAt(Date.from(NOW))
                .expiration(Date.from(exp)).signWith((RSAPrivateKey)signer.getPrivate(),Jwts.SIG.RS256).compact();
    }
    @Test void invalidSignatureAudienceTypeKidAndExpirationAreRejected() {
        Memory store = new Memory(); UserTokens auth = core(store,CLOCK);
        String good=auth.issue("42","blog"), sid=auth.authenticate(good,"blog").sid();
        for (String bad : new String[] {
                token(sid,"knode-api","user","key1",other,NOW.plusSeconds(3600)),
                token(sid,"wrong","user","key1",key,NOW.plusSeconds(3600)),
                token(sid,"knode-api","service","key1",key,NOW.plusSeconds(3600)),
                token(sid,"knode-api","user","unknown",key,NOW.plusSeconds(3600)),
                token(sid,"knode-api","user","key1",key,NOW.minusSeconds(1)) }) {
            denied(() -> auth.authenticate(bad,"blog"),401);
        }
        UserTokens later=core(store,Clock.fixed(NOW.plusSeconds(3600),ZoneOffset.UTC));
        denied(() -> later.authenticate(good,"blog"),401);
    }
    @Test void unavailableStateReturns503AndDoesNotRunHandler() {
        Memory store=new Memory(); UserTokens auth=core(store,CLOCK);
        String token=auth.issue("42","blog"); store.unavailable=true;
        AtomicBoolean invoked=new AtomicBoolean();
        denied(() -> auth.authorized(token,"blog",() -> {invoked.set(true); return null;}),503);
        assertFalse(invoked.get());
        denied(() -> auth.issue("42","blog"),503);
        denied(() -> auth.revoke(token,"blog"),503);
    }

    String rawToken(String header, String payload) throws Exception {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signer.sign());
    }
    @Test void timeClaimsMustBeNumericAndDuplicateHeadersRejected() throws Exception {
        Memory store = new Memory(); UserTokens auth = core(store,CLOCK);
        String good = auth.issue("42", "blog"), sid = auth.authenticate(good,"blog").sid();
        String base = "{\"iss\":\"knode-user\",\"sub\":\"42\",\"aud\":[\"knode-api\"],"
                + "\"token_use\":\"user\",\"sid\":\"" + sid + "\",\"scope\":\"blog\","
                + "\"jti\":\"" + UUID.randomUUID() + "\",\"iat\":";
        String quoted = base + "\"" + NOW.getEpochSecond() + "\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}";
        String numeric = base + NOW.getEpochSecond() + ",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}";
        String badTime = rawToken("{\"alg\":\"RS256\",\"kid\":\"key1\"}", quoted);
        String duplicate = rawToken("{\"alg\":\"RS256\",\"kid\":\"key1\",\"kid\":\"key1\"}", numeric);
        denied(() -> auth.authenticate(badTime,"blog"),401);
        denied(() -> auth.authenticate(duplicate,"blog"),401);
        UserTokens readOnly = new UserTokens(null, null, Map.of("key1",(RSAPublicKey)key.getPublic()), CLOCK, store, null);
        assertEquals("42", readOnly.authenticate(good,"blog").userId());
        assertThrows(IllegalStateException.class, () -> readOnly.issue("42","blog"));
    }

    @Test void servletFilterMapsStatusAndStopsProtectedChain() throws Exception {
        Memory store = new Memory(); UserTokens auth = core(store,CLOCK);
        String token = auth.issue("42","blog");
        var filter = new UserAuthenticationFilter(auth,"blog");
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("token",token);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();
        store.unavailable = true;
        filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus());
        assertNull(chain.getRequest());

        request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("token","bad");
        response = new org.springframework.mock.web.MockHttpServletResponse();
        chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(request,response,chain);
        assertEquals(401,response.getStatus());
        assertNull(chain.getRequest());

        store.unavailable = false;
        request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("token",token);
        response = new org.springframework.mock.web.MockHttpServletResponse();
        chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(request,response,chain);
        assertNotNull(chain.getRequest());
        assertEquals("42",((UserTokens.Principal)request.getAttribute(UserAuthenticationFilter.PRINCIPAL_ATTRIBUTE)).userId());
    }
}
