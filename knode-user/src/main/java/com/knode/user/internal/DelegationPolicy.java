package com.knode.user.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.knode.auth.DelegationTokenVerifier;
import com.knode.auth.ServiceTokenVerifier;
import java.util.*;

/** Externally configured actor/audience/purpose grants; absence disables delegation. */
public final class DelegationPolicy {
    private static final Set<String> PURPOSES=Set.of("CREATE","CONTINUE","EXECUTE","EXCHANGE","SAFE_CHECKPOINT");
    private record Key(String actor,String audience,String purpose) {}
    private final Map<Key,Set<String>> rules;
    private DelegationPolicy(Map<Key,Set<String>> rules){this.rules=Map.copyOf(rules);}
    public static DelegationPolicy disabled(){return new DelegationPolicy(Map.of());}
    public boolean enabled(){return !rules.isEmpty();}
    public static DelegationPolicy load(String content) {
        try {
            if (content==null || content.length()>65536) throw new IllegalArgumentException();
            var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=json.readTree(content);ServicePolicy.fields(root,Set.of("rules"));
            JsonNode list=root.path("rules");if (!list.isArray() || list.isEmpty() || list.size()>100) throw new IllegalArgumentException();
            var rules=new LinkedHashMap<Key,Set<String>>();
            for (JsonNode row:list) {
                ServicePolicy.fields(row,Set.of("actor","audience","purpose","permissions"));
                String actor=ServicePolicy.text(row,"actor"),audience=ServicePolicy.text(row,"audience"),purpose=ServicePolicy.text(row,"purpose");
                if (!ServiceTokenVerifier.validService(actor) || !ServiceTokenVerifier.validService(audience) || !PURPOSES.contains(purpose)) throw new IllegalArgumentException();
                Set<String> permissions=DelegationTokenVerifier.parsePermissions(row.path("permissions"));
                if (rules.putIfAbsent(new Key(actor,audience,purpose),permissions)!=null) throw new IllegalArgumentException();
            }
            return new DelegationPolicy(rules);
        } catch (Exception e) {throw new IllegalArgumentException("Invalid external delegation policy");}
    }
    public Set<String> allowed(String actor,String audience,String purpose) {
        if (!enabled()) throw new InternalFailure(503,"DELEGATION_DISABLED");
        Set<String> result=rules.get(new Key(actor,audience,purpose));
        if (result==null) throw new InternalFailure(403,"DELEGATION_DENIED");
        return result;
    }
}
