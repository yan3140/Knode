package com.knode.user.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Load PEM from a secret environment variable or a secure local file; never log key material. */
public final class KeyMaterial {
    private KeyMaterial() {}
    public static RSAPrivateKey privateFromEnvironment(String variable) {
        String pem = System.getenv(variable);
        if (pem == null) throw new IllegalArgumentException("Private key environment variable is missing");
        return parsePrivate(pem);
    }
    public static RSAPrivateKey privateFromFile(Path file) {
        try { return parsePrivate(Files.readString(file)); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read private key file",e); }
    }
    public static RSAPublicKey publicFromFile(Path file) {
        try {
            byte[] data = decode(Files.readString(file),"PUBLIC KEY");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(data));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid public key file",e); }
    }
    private static RSAPrivateKey parsePrivate(String pem) {
        try {
            byte[] data = decode(pem,"PRIVATE KEY");
            return (RSAPrivateKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid private key"); }
    }
    private static byte[] decode(String pem, String label) {
        String begin="-----BEGIN "+label+"-----", end="-----END "+label+"-----";
        String text=pem.trim();
        if (!text.startsWith(begin) || !text.endsWith(end)) throw new IllegalArgumentException("PEM");
        return Base64.getMimeDecoder().decode(text.substring(begin.length(),text.length()-end.length()));
    }
}
