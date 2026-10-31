package com.knode.user.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.interfaces.*;
import java.util.*;

/** Constructed only from externally supplied PEM and trusted kid-to-file mapping. */
public final class SigningKeyRing {
    private final String activeKid;
    private final RSAPrivateKey privateKey;
    private final Map<String,RSAPublicKey> publicKeys;
    public SigningKeyRing(String activeKid,RSAPrivateKey privateKey,Map<String,RSAPublicKey> keys) {
        if (activeKid==null || !activeKid.matches("[A-Za-z0-9._-]{1,64}") || privateKey==null) throw new IllegalArgumentException("Active signing key required");
        this.publicKeys=Map.copyOf(keys);
        if (!publicKeys.containsKey(activeKid) || publicKeys.values().stream().anyMatch(k -> k.getModulus().bitLength()<2048)
                || !privateKey.getModulus().equals(publicKeys.get(activeKid).getModulus())) throw new IllegalArgumentException("Invalid trusted key set");
        this.activeKid=activeKid;this.privateKey=privateKey;
    }
    public String activeKid() { return activeKid; }
    public RSAPrivateKey privateKey() { return privateKey; }
    public Map<String,RSAPublicKey> publicKeys() { return publicKeys; }
    public static Map<String,RSAPublicKey> readTrustedFile(Path file) {
        try {
            ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=json.readTree(Files.readString(file));
            if (root==null || !root.isObject() || root.isEmpty() || root.size()>32) throw new IllegalArgumentException();
            Map<String,RSAPublicKey> result=new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> {
                if (!e.getKey().matches("[A-Za-z0-9._-]{1,64}") || !e.getValue().isTextual()) throw new IllegalArgumentException();
                result.put(e.getKey(),KeyMaterial.publicFromFile(Path.of(e.getValue().textValue())));
            });
            return Map.copyOf(result);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid trusted public key configuration"); }
    }
}
