package com.knode.user.internal;

import com.knode.auth.ServiceTokenVerifier;
import com.knode.user.auth.SigningKeyRing;
import io.jsonwebtoken.Jwts;
import java.time.*;
import java.util.*;

public final class ServiceTokens {
    private final SigningKeyRing keys;
    private final ServicePolicy policy;
    private final Clock clock;
    private final ServiceTokenVerifier verifier;
    public record Issued(String accessToken,String tokenType,int expiresIn,String audience,List<String> permissions) {}
    public ServiceTokens(SigningKeyRing keys,ServicePolicy policy,Clock clock) {
        this.keys=keys;this.policy=policy;this.clock=clock;this.verifier=new ServiceTokenVerifier(keys.publicKeys(),clock);
    }
    public boolean enabled() {return policy.enabled();}
    public Issued issue(String service,String secret,String audience,List<String> requested) {
        Set<String> granted=policy.authorize(service,secret,audience,requested);
        long now=clock.instant().getEpochSecond();int ttl=policy.ttl();List<String> permissions=List.copyOf(granted);
        String jwt=Jwts.builder().header().keyId(keys.activeKid()).and().issuer("knode-user").subject(service)
                .audience().add(audience).and().claim("token_use","service").claim("permissions",permissions)
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(Instant.ofEpochSecond(now)))
                .expiration(Date.from(Instant.ofEpochSecond(now+ttl))).signWith(keys.privateKey(),Jwts.SIG.RS256).compact();
        return new Issued(jwt,"Bearer",ttl,audience,permissions);
    }
    public ServiceTokenVerifier.Principal verify(String token,String permission) {
        if (!enabled()) throw new InternalFailure(503,"SERVICE_ISSUER_UNAVAILABLE");
        return verifier.verify(token,"knode-user",Set.of(permission));
    }
    public Map<String,Object> jwks() {
        List<Map<String,String>> list=new ArrayList<>();
        keys.publicKeys().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            var key=e.getValue();var b64=Base64.getUrlEncoder().withoutPadding();
            list.add(Map.of("kty","RSA","use","sig","alg","RS256","kid",e.getKey(),
                    "n",b64.encodeToString(unsigned(key.getModulus().toByteArray())),
                    "e",b64.encodeToString(unsigned(key.getPublicExponent().toByteArray()))));
        });
        return Map.of("keys",list);
    }
    private static byte[] unsigned(byte[] value) {return value.length>1 && value[0]==0 ? Arrays.copyOfRange(value,1,value.length):value;}
}
