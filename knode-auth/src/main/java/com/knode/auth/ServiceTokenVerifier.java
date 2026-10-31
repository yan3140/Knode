package com.knode.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;

/** Pure CPU verification; no user session, private key, web or Redis dependency. */
public final class ServiceTokenVerifier {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String,RSAPublicKey> keys;
    private final Clock clock;
    public record Principal(String service,String audience,Set<String> permissions,long expiresAt) {
        public Principal { permissions=Set.copyOf(permissions); }
    }
    public ServiceTokenVerifier(Map<String,RSAPublicKey> keys,Clock clock) {
        this.keys=Map.copyOf(keys);this.clock=Objects.requireNonNull(clock);
        if (this.keys.isEmpty() || this.keys.values().stream().anyMatch(k -> k.getModulus().bitLength()<2048))
            throw new IllegalArgumentException("RSA public keys must be at least 2048 bits");
    }
    public Principal verify(String token,String audience,Set<String> requiredPermissions) {
        if (!validService(audience) || requiredPermissions==null || requiredPermissions.stream().anyMatch(p -> !validPermission(p)))
            throw new IllegalArgumentException("Invalid verifier policy");
        Principal principal;
        try {
            if (token==null || token.length()>8192) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);
            if (parts.length!=3 || parts[0].length()>2048) throw new IllegalArgumentException();
            JsonNode h=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode p=JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (h==null || !h.isObject() || !"RS256".equals(h.path("alg").textValue()) || !h.path("kid").isTextual()
                    || p==null || !p.isObject() || !p.path("iat").isIntegralNumber() || !p.path("iat").canConvertToLong()
                    || !p.path("exp").isIntegralNumber() || !p.path("exp").canConvertToLong()
                    || !p.path("permissions").isArray() || p.path("permissions").isEmpty()
                    || p.has("sid") || p.has("scope") || p.has("user_scope")) throw new IllegalArgumentException();
            RSAPublicKey key=keys.get(h.path("kid").textValue());if (key==null) throw new IllegalArgumentException();
            var c=Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload();
            long now=clock.instant().getEpochSecond();
            var iat=c.getIssuedAt();var exp=c.getExpiration();String sub=c.getSubject();
            if (!"knode-user".equals(c.getIssuer()) || !"service".equals(c.get("token_use",String.class))
                    || !validService(sub) || !Set.of(audience).equals(c.getAudience()) || !UserTokenVerifier.validUuid(c.getId())
                    || iat==null || exp==null || iat.toInstant().getEpochSecond()>now || exp.toInstant().getEpochSecond()<=now
                    || exp.toInstant().getEpochSecond()<=iat.toInstant().getEpochSecond()
                    || exp.toInstant().getEpochSecond()-iat.toInstant().getEpochSecond()>300) throw new IllegalArgumentException();
            var permissions=new LinkedHashSet<String>();
            if (p.get("permissions").size()>100) throw new IllegalArgumentException();
            for (JsonNode n:p.get("permissions")) if (!n.isTextual() || !validPermission(n.textValue()) || !permissions.add(n.textValue())) throw new IllegalArgumentException();
            principal=new Principal(sub,audience,permissions,exp.toInstant().getEpochSecond());
        } catch (Exception e) { throw new AuthFailure(401); }
        if (!principal.permissions().containsAll(requiredPermissions)) throw new AuthFailure(403);
        return principal;
    }
    public static boolean validService(String value) { return value!=null && value.matches("[a-z][a-z0-9-]{0,63}"); }
    public static boolean validPermission(String value) { return value!=null && value.matches("[a-z][a-z0-9:._-]{0,127}"); }
}
