package com.knode.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SharedVerifierTest {
    @Test void asyncCallerCanVerifyThenApplyAuthoritativeStateBeforeScope() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        var now=Instant.parse("2026-01-01T00:00:00Z"); var clock=Clock.fixed(now,ZoneOffset.UTC);
        String sid=UUID.randomUUID().toString();
        String jwt=Jwts.builder().header().keyId("k").and().issuer("knode-user").subject("42")
                .audience().add("knode-api").and().claim("token_use","user").claim("sid",sid).claim("scope","blog")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith((RSAPrivateKey)pair.getPrivate(),Jwts.SIG.RS256).compact();
        var verifier=new UserTokenVerifier(Map.of("k",(RSAPublicKey)pair.getPublic()),clock);
        var verified=verifier.verifyToken(jwt);
        var state=new Session(1,sid,"42","blog","ACTIVE",now.getEpochSecond()+3600);
        assertEquals(state,SessionCodec.decode(SessionCodec.encode(state)));
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.validateSession(verified,Optional.of(state),"admin")).status());
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.validateSession(verified,Optional.empty(),"admin")).status());
        var revoked=new Session(1,sid,"42","blog","REVOKED",state.expiresAt());
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.validateSession(verified,Optional.of(revoked),"admin")).status());
        var expiredVerifier=new UserTokenVerifier(Map.of("k",(RSAPublicKey)pair.getPublic()),
                Clock.fixed(now.plusSeconds(3600),ZoneOffset.UTC));
        assertEquals(401,assertThrows(AuthFailure.class,() -> expiredVerifier.validateSession(verified,Optional.of(state),"admin")).status());
        assertEquals(503,assertThrows(AuthFailure.class,() -> SessionCodec.decode("{broken")).status());
        assertEquals(503,assertThrows(AuthFailure.class,() -> SessionCodec.decode(SessionCodec.encode(state)+" {}")).status());
        assertEquals(503,assertThrows(AuthFailure.class,() -> SessionCodec.decode(SessionCodec.encode(state).replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""))).status());
    }
}
