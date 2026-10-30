package com.knode.gateway;

import io.jsonwebtoken.Jwts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationConfigurationTest {
    @TempDir Path directory;
    @Test void publicKeyMappingAcceptsMultilinePemAndRejectsUnknownKid() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(pair.getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----\n";
        Path file = directory.resolve("public.pem"); Files.writeString(file, pem);
        var material = new AuthenticationConfiguration().authMaterial("key-one=" + file + ";key-two=" + file, 1000);
        var now = Instant.now();
        var jwt = Jwts.builder().header().keyId("key-one").and().issuer("knode-user").subject("42")
                .audience().add("knode-api").and().claim("token_use", "user")
                .claim("sid", UUID.randomUUID().toString()).claim("scope", "blog")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(120)))
                .signWith((RSAPrivateKey) pair.getPrivate(), Jwts.SIG.RS256).compact();
        assertThat(material.verifier().verifyToken(jwt).userId()).isEqualTo("42");
        var rotated = Jwts.builder().header().keyId("key-two").and().issuer("knode-user").subject("42")
                .audience().add("knode-api").and().claim("token_use", "user")
                .claim("sid", UUID.randomUUID().toString()).claim("scope", "blog")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(120)))
                .signWith((RSAPrivateKey) pair.getPrivate(), Jwts.SIG.RS256).compact();
        assertThat(material.verifier().verifyToken(rotated).userId()).isEqualTo("42");
        var unknown = Jwts.builder().header().keyId("untrusted").and().issuer("knode-user").subject("42")
                .audience().add("knode-api").and().claim("token_use", "user")
                .claim("sid", UUID.randomUUID().toString()).claim("scope", "blog")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(120)))
                .signWith((RSAPrivateKey) pair.getPrivate(), Jwts.SIG.RS256).compact();
        assertThatThrownBy(() -> material.verifier().verifyToken(unknown))
                .isInstanceOf(com.knode.auth.AuthFailure.class);
        assertThatThrownBy(() -> new AuthenticationConfiguration().authMaterial("same=" + file + ";same=" + file, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        Path privateFile = directory.resolve("private.pem");
        Files.writeString(privateFile, "-----BEGIN PRIVATE KEY-----\nZmFrZQ==\n-----END PRIVATE KEY-----");
        assertThatThrownBy(() -> new AuthenticationConfiguration().authMaterial("bad=" + privateFile, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
