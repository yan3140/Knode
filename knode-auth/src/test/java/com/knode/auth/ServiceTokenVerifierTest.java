package com.knode.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTokenVerifierTest {
    @Test void verifiesRotationTypeAudiencePermissionsAndLifetime() throws Exception {
        var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);var old=g.generateKeyPair();var current=g.generateKeyPair();var other=g.generateKeyPair();
        Instant now=Instant.parse("2026-01-01T00:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        var keys=Map.of("old",(RSAPublicKey)old.getPublic(),"new",(RSAPublicKey)current.getPublic());
        var verifier=new ServiceTokenVerifier(keys,clock);
        String oldToken=issue(old,"old","service","knode-user",List.of("users:public:read"),now,120,false);
        String newToken=issue(current,"new","service","knode-user",List.of("users:public:read"),now,120,false);
        assertEquals("knode-content",verifier.verify(oldToken,"knode-user",Set.of("users:public:read")).service());
        assertEquals("knode-content",verifier.verify(newToken,"knode-user",Set.of()).service());
        var retired=new ServiceTokenVerifier(Map.of("new",(RSAPublicKey)current.getPublic()),clock);
        assertEquals(401,assertThrows(AuthFailure.class,() -> retired.verify(oldToken,"knode-user",Set.of())).status());
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.verify(newToken,"knode-user",Set.of("users:state:read"))).status());
        for (String bad:List.of(issue(other,"new","service","knode-user",List.of("users:public:read"),now,120,false),
                issue(old,"unknown","service","knode-user",List.of("users:public:read"),now,120,false),
                issue(old,"old","user","knode-user",List.of("users:public:read"),now,120,false),
                issue(old,"old","service","other-service",List.of("users:public:read"),now,120,false),
                issue(old,"old","service","knode-user",List.of("users:public:read"),now,301,false),
                issue(old,"old","service","knode-user",List.of("users:public:read"),now,120,true)))
            assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.verify(bad,"knode-user",Set.of())).status());
        var userVerifier=new UserTokenVerifier(keys,clock);
        assertEquals(401,assertThrows(AuthFailure.class,() -> userVerifier.verifyToken(newToken)).status());
    }
    private String issue(KeyPair key,String kid,String type,String audience,List<String> perms,Instant now,int ttl,boolean sid) {
        var b=Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject("knode-content")
                .audience().add(audience).and().claim("token_use",type).claim("permissions",perms).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl)));
        if(sid)b.claim("sid",UUID.randomUUID().toString());
        return b.signWith((RSAPrivateKey)key.getPrivate(),Jwts.SIG.RS256).compact();
    }
}
