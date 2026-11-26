package com.knode.search.auth;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TrustedPublicKeyLoader {
    public static final int MAX_TRUSTED_KIDS = 4;
    public static final int MIN_RSA_KEY_BITS = 2048;

    private TrustedPublicKeyLoader() {
    }

    public static Map<String, RSAPublicKey> loadUserKeys(SearchAuthProperties properties) {
        Map<String, String> raw = new LinkedHashMap<>();
        if (StringUtils.hasText(properties.getPublicKeyId()) || StringUtils.hasText(properties.getPublicKeyFile())) {
            if (!StringUtils.hasText(properties.getPublicKeyId()) || !StringUtils.hasText(properties.getPublicKeyFile())) {
                throw new IllegalStateException("Both knode.search.auth.public-key-id and public-key-file must be set together");
            }
            raw.put(properties.getPublicKeyId().trim(), properties.getPublicKeyFile().trim());
        }
        if (properties.getTrustedPublicKeys() != null) {
            for (Map.Entry<String, String> entry : properties.getTrustedPublicKeys().entrySet()) {
                String kid = entry.getKey() == null ? "" : entry.getKey().trim();
                String val = entry.getValue() == null ? "" : entry.getValue().trim();
                if (!StringUtils.hasText(kid) || !StringUtils.hasText(val)) {
                    throw new IllegalStateException("Trusted public key entries must have non-blank kid and PEM/file value");
                }
                if (raw.containsKey(kid)) {
                    throw new IllegalStateException("Duplicate trusted public key kid: " + kid);
                }
                raw.put(kid, val);
            }
        }
        return parseKeyMap(raw);
    }

    public static Map<String, RSAPublicKey> loadServiceKeys(
            SearchAuthProperties properties, Map<String, RSAPublicKey> fallbackUserKeys) {
        if (properties.getServiceTrustedPublicKeys() == null || properties.getServiceTrustedPublicKeys().isEmpty()) {
            return fallbackUserKeys;
        }
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.getServiceTrustedPublicKeys().entrySet()) {
            String kid = entry.getKey() == null ? "" : entry.getKey().trim();
            String val = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!StringUtils.hasText(kid) || !StringUtils.hasText(val)) {
                throw new IllegalStateException("Service trusted public key entries must have non-blank kid and PEM/file value");
            }
            raw.put(kid, val);
        }
        return parseKeyMap(raw);
    }

    public static Map<String, RSAPublicKey> parseKeyMap(Map<String, String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return Map.of();
        }
        if (rawKeys.size() > MAX_TRUSTED_KIDS) {
            throw new IllegalStateException("At most " + MAX_TRUSTED_KIDS + " overlapping trusted RSA public keys are allowed");
        }
        Map<String, RSAPublicKey> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawKeys.entrySet()) {
            String kid = entry.getKey().trim();
            String pem = resolvePem(entry.getValue().trim());
            RSAPublicKey rsaKey = parseRsaPublicKeyPem(pem, kid);
            loaded.put(kid, rsaKey);
        }
        return Collections.unmodifiableMap(loaded);
    }

    private static String resolvePem(String source) {
        if (source.contains("BEGIN PUBLIC KEY")) {
            return source;
        }
        String filePath = source.startsWith("file:") ? source.substring("file:".length()) : source;
        try {
            return Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read RSA public key file: " + filePath, ex);
        }
    }

    public static RSAPublicKey parseRsaPublicKeyPem(String pem, String kid) {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Empty RSA public key PEM for kid " + kid);
        }
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof RSAPublicKey rsaKey)) {
                throw new IllegalStateException("Public key is not RSA for kid " + kid);
            }
            if (rsaKey.getModulus().bitLength() < MIN_RSA_KEY_BITS) {
                throw new IllegalStateException("RSA public key for kid " + kid + " is smaller than 2048 bits");
            }
            return rsaKey;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid RSA public key PEM for kid " + kid, ex);
        }
    }
}
