package com.knode.auth;

import io.jsonwebtoken.Jwts;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DelegationTokenVerifierTest {
    @Test void cpuVerificationNeverConfusesProofOrCheckpointWithFinalAuthorization() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var key=generator.generateKeyPair();
        var clock=Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC);
        var verifier=new DelegationTokenVerifier(Map.of("test",(RSAPublicKey)key.getPublic()),clock);
        String sid=UUID.randomUUID().toString(),grant=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString();
        Map<String,String> resource=Map.of("knowledgeBaseId","42","clientRequestId","req-1");
        String token=token(key,clock,sid,grant,jti,"CREATE","NORMAL","knode-agent",resource,Set.of("agent:runs:create"));
        var verified=verifier.verifyNormal(token,"knode-agent");
        var actor=new ServiceTokenVerifier.Principal("knode-knowledge","knode-agent",Set.of("agent:runs:create"),clock.instant().getEpochSecond()+60);
        var proof=new DelegationTokenVerifier.CurrentProof(grant,"42",sid,"blog","knode-knowledge","knode-agent",resource,Set.of("agent:runs:create"),clock.instant().getEpochSecond()+60,clock.instant().getEpochSecond()+120,true);
        var session=new Session(1,sid,"42","blog","ACTIVE",clock.instant().getEpochSecond()+120);
        assertEquals("42",verifier.authorize(verified,actor,Optional.of(session),proof,Set.of("agent:runs:create"),resource).userId());
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.authorize(verified,actor,Optional.empty(),proof,Set.of("agent:runs:create"),resource)).status());
        var wrongActor=new ServiceTokenVerifier.Principal("knode-other","knode-agent",Set.of(),clock.instant().getEpochSecond()+60);
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.authorize(verified,wrongActor,Optional.of(session),proof,Set.of("agent:runs:create"),resource)).status());
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.authorize(verified,actor,Optional.of(session),proof,Set.of("agent:runs:delete"),resource)).status());
        var noPermission=new ServiceTokenVerifier.Principal("knode-knowledge","knode-agent",Set.of(),clock.instant().getEpochSecond()+60);
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.authorize(verified,noPermission,Optional.of(session),proof,Set.of("agent:runs:create"),resource)).status());
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.authorize(verified,actor,Optional.of(session),proof,Set.of(),resource)).status());
        var otherKey=generator.generateKeyPair();
        String forged=token(otherKey,clock,sid,grant,jti,"CREATE","NORMAL","knode-agent",resource,Set.of("agent:runs:create"));
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.verifyNormal(forged,"knode-agent")).status());
        var unknownKid=new DelegationTokenVerifier(Map.of("other",(RSAPublicKey)key.getPublic()),clock);
        assertEquals(401,assertThrows(AuthFailure.class,() -> unknownKid.verifyNormal(token,"knode-agent")).status());
        var expiredClock=Clock.offset(clock,Duration.ofSeconds(61));
        assertEquals(401,assertThrows(AuthFailure.class,() -> new DelegationTokenVerifier(Map.of("test",(RSAPublicKey)key.getPublic()),expiredClock).verifyNormal(token,"knode-agent")).status());
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.verifyNormal(token,"knode-other")).status());
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.verifyNormal(token+"x".repeat(8193),"knode-agent")).status());
        Map<String,String> step=new LinkedHashMap<>(resource);step.put("runId","run-1");step.put("controlEpoch","1");step.put("workerId","worker-1");step.put("stepId","step-1");step.put("checkpointOperationId","op-1");
        String checkpoint=token(key,clock,sid,grant,UUID.randomUUID().toString(),"SAFE_CHECKPOINT","SAFE_CHECKPOINT","knode-agent",step,Set.of("agent:checkpoint:write"));
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier.verifyNormal(checkpoint,"knode-agent")).status());
        var verifiedCheckpoint=verifier.verifyCheckpoint(checkpoint,"knode-agent");
        var cpProof=new DelegationTokenVerifier.CurrentProof(grant,"42",sid,"blog","knode-knowledge","knode-agent",step,Set.of("agent:checkpoint:write"),clock.instant().getEpochSecond()+60,clock.instant().getEpochSecond()+120,true);
        var checkpointActor=new ServiceTokenVerifier.Principal("knode-knowledge","knode-agent",Set.of("agent:checkpoint:write"),clock.instant().getEpochSecond()+60);
        assertEquals("op-1",verifier.authorizeCheckpoint(verifiedCheckpoint,checkpointActor,cpProof,step).resource().get("checkpointOperationId"));
        assertEquals(403,assertThrows(AuthFailure.class,() -> verifier.authorizeCheckpoint(verifiedCheckpoint,actor,cpProof,step)).status());
    }
    private static String token(KeyPair key,Clock clock,String sid,String grant,String jti,String purpose,String use,String audience,Map<String,String> resource,Set<String> permissions) {
        long now=clock.instant().getEpochSecond();
        return Jwts.builder().header().keyId("test").and().issuer("knode-user").subject("42").audience().add(audience).and()
                .claim("token_use","delegation").claim("sid",sid).claim("user_scope","blog").claim("act",Map.of("sub","knode-knowledge"))
                .claim("grantId",grant).claim("purpose",purpose).claim("delegation_use",use).claim("resource",resource).claim("permissions",permissions)
                .id(jti).issuedAt(Date.from(Instant.ofEpochSecond(now))).expiration(Date.from(Instant.ofEpochSecond(now+60)))
                .signWith((RSAPrivateKey)key.getPrivate(),Jwts.SIG.RS256).compact();
    }
}
