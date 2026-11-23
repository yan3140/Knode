package com.knode.content.relay;

import com.knode.auth.AuthFailure;
import com.knode.auth.ServiceTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MachineAuth {
    public static final String PRINCIPAL=MachineAuth.class.getName()+".principal";
    private final ServiceTokenVerifier verifier;
    public MachineAuth(@Value("${KNODE_CONTENT_OPS_PUBLIC_KEYS:}") String config) {
        if(config.isBlank()) { verifier=null;return; }
        try {
            String[] entries=config.split(";",-1);
            if(entries.length<1 || entries.length>4) throw new IllegalArgumentException();
            Map<String,RSAPublicKey> keys=new HashMap<>();
            for(String entry:entries) {
                int at=entry.indexOf('=');
                if(at<1) throw new IllegalArgumentException();
                String kid=entry.substring(0,at),name=entry.substring(at+1);
                if(!kid.matches("[A-Za-z0-9._-]{1,64}") || name.isBlank()) throw new IllegalArgumentException();
                Path path=Path.of(name);
                if(!path.isAbsolute() || Files.size(path)>8192) throw new IllegalArgumentException();
                String pem=Files.readString(path);
                if(!pem.startsWith("-----BEGIN PUBLIC KEY-----") || !pem.contains("-----END PUBLIC KEY-----")) throw new IllegalArgumentException();
                String body=pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
                RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
                if(key.getModulus().bitLength()<2048 || keys.putIfAbsent(kid,key)!=null) throw new IllegalArgumentException();
            }
            verifier=new ServiceTokenVerifier(Map.copyOf(keys),Clock.systemUTC());
        } catch(Exception failure) { throw new IllegalArgumentException("Invalid ops public keys"); }
    }
    public ServiceTokenVerifier.Principal authenticate(HttpServletRequest request,String permission) {
        List<String> headers=Collections.list(request.getHeaders("Authorization"));
        if(headers.size()!=1 || !headers.get(0).startsWith("Bearer ") || headers.get(0).contains(",")
                || !Collections.list(request.getHeaders("token")).isEmpty()
                || !Collections.list(request.getHeaders("Cookie")).isEmpty()
                || !Collections.list(request.getHeaders("Proxy-Authorization")).isEmpty()
                || !Collections.list(request.getHeaders("X-Api-Key")).isEmpty()) throw new AuthFailure(401);
        if(Collections.list(request.getHeaderNames()).stream().map(x -> x.toLowerCase(Locale.ROOT))
                .anyMatch(x -> x.equals("x-actor") || x.startsWith("x-actor-") || x.equals("x-operator") || x.startsWith("x-operator-")))
            throw new AuthFailure(401);
        String compact=headers.get(0).substring(7);
        if(compact.isBlank() || compact.length()>8192 || compact.chars().anyMatch(Character::isWhitespace)) throw new AuthFailure(401);
        if(verifier==null) throw new AuthFailure(503);
        return verifier.verify(compact,"knode-content",Set.of(permission));
    }
}
