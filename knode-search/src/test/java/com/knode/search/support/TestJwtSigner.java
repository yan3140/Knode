package com.knode.search.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class TestJwtSigner {
    private final String kid;
    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private TestJwtSigner(String kid, RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.kid = kid;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static TestJwtSigner generate(String kid) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            return new TestJwtSigner(kid, (RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA-2048 keypair", ex);
        }
    }

    public String kid() {
        return kid;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String publicKeyPem() {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    public String signUserToken(String userId, String sid, String scope, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer("knode-user")
                .subject(userId)
                .audience().add("knode-api").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("token_use", "user")
                .claim("sid", sid)
                .claim("scope", scope)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String signServiceToken(String callerService, String audience, List<String> permissions, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer("knode-user")
                .subject(callerService)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("token_use", "service")
                .claim("permissions", permissions)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
