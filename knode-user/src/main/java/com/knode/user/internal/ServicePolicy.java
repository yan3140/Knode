package com.knode.user.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.knode.auth.ServiceTokenVerifier;
import java.nio.file.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Policy content must be loaded from an external secret file or external environment variable. */
public final class ServicePolicy {
    private final Map<String,Entry> services;
    private final int ttl;
    private final BCryptPasswordEncoder bcrypt;
    private record Entry(String hash,Map<String,Set<String>> audiences) {}
    private ServicePolicy(Map<String,Entry> services,int ttl,BCryptPasswordEncoder bcrypt) {this.services=Map.copyOf(services);this.ttl=ttl;this.bcrypt=bcrypt;}
    public static ServicePolicy disabled(BCryptPasswordEncoder bcrypt) {return new ServicePolicy(Map.of(),120,bcrypt);}
    public boolean enabled() {return !services.isEmpty();}
    public int ttl() {return ttl;}
    public static ServicePolicy load(String content,BCryptPasswordEncoder bcrypt) {
        try {
            ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(content);fields(root,Set.of("ttlSeconds","services"));
            int ttl=root.has("ttlSeconds") && root.get("ttlSeconds").isInt()?root.get("ttlSeconds").intValue():120;
            if (root.has("ttlSeconds") && !root.get("ttlSeconds").isInt() || ttl<1 || ttl>300) throw new IllegalArgumentException();
            JsonNode serviceNodes=root.path("services");if (!serviceNodes.isArray() || serviceNodes.isEmpty() || serviceNodes.size()>100) throw new IllegalArgumentException();
            Map<String,Entry> entries=new LinkedHashMap<>();
            for (JsonNode s:serviceNodes) {
                fields(s,Set.of("id","credentialHash","audiences"));
                String id=text(s,"id"),hash=text(s,"credentialHash");
                if (!ServiceTokenVerifier.validService(id) || !hash.matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")) throw new IllegalArgumentException();
                int cost=Integer.parseInt(hash.substring(4,6));
                if (cost<4 || cost>16) throw new IllegalArgumentException();
                JsonNode audiences=s.path("audiences");if (!audiences.isObject() || audiences.isEmpty()) throw new IllegalArgumentException();
                Map<String,Set<String>> grants=new LinkedHashMap<>();
                audiences.fields().forEachRemaining(e -> {
                    if (!ServiceTokenVerifier.validService(e.getKey()) || !e.getValue().isArray() || e.getValue().isEmpty() || e.getValue().size()>100) throw new IllegalArgumentException();
                    Set<String> permissions=new LinkedHashSet<>();
                    e.getValue().forEach(n -> {if (!n.isTextual() || !ServiceTokenVerifier.validPermission(n.textValue()) || !permissions.add(n.textValue())) throw new IllegalArgumentException();});
                    grants.put(e.getKey(),Set.copyOf(permissions));
                });
                if (entries.putIfAbsent(id,new Entry(hash,Map.copyOf(grants)))!=null) throw new IllegalArgumentException();
            }
            return new ServicePolicy(entries,ttl,bcrypt);
        } catch (Exception e) {throw new IllegalArgumentException("Invalid external service policy");}
    }
    static void fields(JsonNode node,Set<String> allowed) {
        if (node==null || !node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(n -> {if (!allowed.contains(n)) throw new IllegalArgumentException();});
    }
    static String text(JsonNode n,String name) {
        if (!n.path(name).isTextual() || n.path(name).textValue().isBlank()) throw new IllegalArgumentException();
        return n.path(name).textValue();
    }
    public Set<String> authorize(String service,String secret,String audience,List<String> requested) {
        if (!enabled()) throw new InternalFailure(503,"SERVICE_ISSUER_UNAVAILABLE");
        Entry entry=services.get(service);
        if (entry==null || secret==null || secret.length()>128 || !bcrypt.matches(secret,entry.hash)) throw new InternalFailure(401,"INVALID_CLIENT_CREDENTIALS");
        Set<String> allowed=entry.audiences.get(audience);
        if (allowed==null) throw new InternalFailure(403,"SERVICE_PERMISSION_DENIED");
        LinkedHashSet<String> granted=new LinkedHashSet<>();
        for (String p:requested) if (allowed.contains(p)) granted.add(p);
        if (granted.isEmpty()) throw new InternalFailure(403,"SERVICE_PERMISSION_DENIED");
        return Collections.unmodifiableSet(granted);
    }
}
