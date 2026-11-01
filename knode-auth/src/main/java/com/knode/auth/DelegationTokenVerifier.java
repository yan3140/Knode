package com.knode.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;

/** CPU-only signature/claim verification. Online state must be supplied separately to authorize. */
public final class DelegationTokenVerifier {
    public static final int MAX_TOKEN_LENGTH=8192;
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RESOURCE_FIELDS=Set.of("knowledgeBaseId","clientRequestId","conversationId","runId","controlEpoch","workerId","stepId","checkpointOperationId");
    private final Map<String,RSAPublicKey> keys;
    private final Clock clock;

    private record Claims(String userId,String sid,String userScope,String actor,String audience,String grantId,
                          String jti,String purpose,long issuedAt,long expiresAt,Set<String> permissions,Map<String,String> resource) {
        private Claims {permissions=Set.copyOf(permissions);resource=Map.copyOf(resource);}
    }
    /** Only proves local signature and claims. It is not an authorization decision. */
    public static final class Verified {
        private final Claims c;
        private Verified(Claims c) {this.c=c;}
        public String userId(){return c.userId();} public String sid(){return c.sid();}
        public String userScope(){return c.userScope();} public String actor(){return c.actor();}
        public String audience(){return c.audience();} public String grantId(){return c.grantId();}
        public String jti(){return c.jti();} public String purpose(){return c.purpose();}
        public long expiresAt(){return c.expiresAt();} public Set<String> permissions(){return c.permissions();}
        public Map<String,String> resource(){return c.resource();}
    }
    /** Separate capability type: it cannot be passed to normal authorization. */
    public static final class VerifiedCheckpoint {
        private final Claims c;
        private VerifiedCheckpoint(Claims c) {this.c=c;}
        public String userId(){return c.userId();} public String sid(){return c.sid();}
        public String userScope(){return c.userScope();} public String actor(){return c.actor();}
        public String audience(){return c.audience();} public String grantId(){return c.grantId();}
        public String jti(){return c.jti();} public long expiresAt(){return c.expiresAt();}
        public Map<String,String> resource(){return c.resource();}
    }
    /** Must be populated only after current grant and domain authority checks, never from a request body. */
    public record CurrentProof(String grantId,String userId,String sid,String userScope,String actor,String audience,
                               Map<String,String> resource,Set<String> permissions,long validUntil,long authorizationExpiresAt,boolean active) {
        public CurrentProof {resource=Map.copyOf(resource);permissions=Set.copyOf(permissions);}
    }
    public static final class Authorized {
        private final String userId,sid,actor,audience,grantId;
        private final Map<String,String> resource;
        private Authorized(Claims c) {userId=c.userId();sid=c.sid();actor=c.actor();audience=c.audience();grantId=c.grantId();resource=c.resource();}
        public String userId(){return userId;} public String sid(){return sid;} public String actor(){return actor;}
        public String audience(){return audience;} public String grantId(){return grantId;} public Map<String,String> resource(){return resource;}
    }
    /** Narrow validation result, not evidence that a checkpoint transaction committed. */
    public static final class AuthorizedCheckpoint {
        private final String jti,grantId;
        private final Map<String,String> resource;
        private AuthorizedCheckpoint(Claims c) {jti=c.jti();grantId=c.grantId();resource=c.resource();}
        public String jti(){return jti;} public String grantId(){return grantId;} public Map<String,String> resource(){return resource;}
    }

    public DelegationTokenVerifier(Map<String,RSAPublicKey> keys,Clock clock) {
        this.keys=Map.copyOf(keys);this.clock=Objects.requireNonNull(clock);
        if (this.keys.isEmpty() || this.keys.values().stream().anyMatch(k -> k.getModulus().bitLength()<2048))
            throw new IllegalArgumentException("RSA public keys must be at least 2048 bits");
    }
    public Verified verifyNormal(String token,String audience) {return new Verified(verify(token,audience,false));}
    public VerifiedCheckpoint verifyCheckpoint(String token,String audience) {return new VerifiedCheckpoint(verify(token,audience,true));}
    private Claims verify(String token,String audience,boolean checkpoint) {
        if (!ServiceTokenVerifier.validService(audience)) throw new IllegalArgumentException("Invalid audience");
        try {
            if (token==null || token.length()>MAX_TOKEN_LENGTH) throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);if (parts.length!=3 || parts[0].length()>2048) throw new IllegalArgumentException();
            JsonNode h=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode p=JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (h==null || !h.isObject() || !"RS256".equals(h.path("alg").textValue()) || !h.path("kid").isTextual()
                    || p==null || !p.isObject() || !p.path("iat").isIntegralNumber() || !p.path("iat").canConvertToLong()
                    || !p.path("exp").isIntegralNumber() || !p.path("exp").canConvertToLong()
                    || p.has("scope") || !singleAudience(p.path("aud"),audience)) throw new IllegalArgumentException();
            var key=keys.get(h.path("kid").textValue());if (key==null) throw new IllegalArgumentException();
            var c=Jwts.parser().verifyWith(key).clock(() -> new Date(clock.millis())).build().parseSignedClaims(token).getPayload();
            long iat=p.path("iat").longValue(),exp=p.path("exp").longValue(),now=clock.instant().getEpochSecond();
            String user=c.getSubject(),sid=text(p,"sid"),scope=text(p,"user_scope"),grant=text(p,"grantId"),jti=c.getId();
            String purpose=text(p,"purpose"),use=text(p,"delegation_use");
            JsonNode act=p.path("act");if (!act.isObject() || act.size()!=1) throw new IllegalArgumentException();
            String actor=text(act,"sub");
            if (!"knode-user".equals(c.getIssuer()) || !"delegation".equals(c.get("token_use",String.class))
                    || !Set.of(audience).equals(c.getAudience()) || !validId(user) || !UserTokenVerifier.validUuid(sid)
                    || !UserTokenVerifier.validScope(scope) || !UserTokenVerifier.validUuid(grant) || !UserTokenVerifier.validUuid(jti)
                    || !ServiceTokenVerifier.validService(actor) || iat<0 || iat>now || exp<=now || exp<=iat
                    || exp-iat>(checkpoint?300:60) || (checkpoint?!"SAFE_CHECKPOINT".equals(use):!"NORMAL".equals(use)))
                throw new IllegalArgumentException();
            if (checkpoint?!"SAFE_CHECKPOINT".equals(purpose):!Set.of("CREATE","CONTINUE","EXECUTE","EXCHANGE").contains(purpose))
                throw new IllegalArgumentException();
            Set<String> permissions=parsePermissions(p.path("permissions"));
            if (checkpoint && !permissions.equals(Set.of("agent:checkpoint:write"))) throw new IllegalArgumentException();
            Map<String,String> resource=parseResource(p.path("resource"),purpose);
            return new Claims(user,sid,scope,actor,audience,grant,jti,purpose,iat,exp,permissions,resource);
        } catch (Exception e) {throw new AuthFailure(401);}
    }
    public Authorized authorize(Verified token,ServiceTokenVerifier.Principal actor,Optional<Session> session,
                                CurrentProof proof,Set<String> required,Map<String,String> resource) {
        Objects.requireNonNull(token);Claims c=token.c;
        checkActor(c,actor,required);
        Session s=session.orElseThrow(() -> new AuthFailure(401));
        if (s.schemaVersion()!=1 || !c.sid().equals(s.sid()) || !c.userId().equals(s.userId()) || !c.userScope().equals(s.scope())
                || !"ACTIVE".equals(s.status()) || s.expiresAt()<c.expiresAt() || s.expiresAt()<=clock.instant().getEpochSecond())
            throw new AuthFailure(401);
        checkProof(c,proof,required,resource);
        return new Authorized(c);
    }
    public AuthorizedCheckpoint authorizeCheckpoint(VerifiedCheckpoint token,ServiceTokenVerifier.Principal actor,
                                                      CurrentProof proof,Map<String,String> resource) {
        Objects.requireNonNull(token);Claims c=token.c;
        checkActor(c,actor,Set.of("agent:checkpoint:write"));checkProof(c,proof,Set.of("agent:checkpoint:write"),resource);
        return new AuthorizedCheckpoint(c);
    }
    private void checkActor(Claims c,ServiceTokenVerifier.Principal actor,Set<String> required) {
        if (actor==null || !c.actor().equals(actor.service()) || !c.audience().equals(actor.audience())
                || actor.expiresAt()<=clock.instant().getEpochSecond() || c.expiresAt()<=clock.instant().getEpochSecond())
            throw new AuthFailure(401);
        if (required==null || required.isEmpty() || !actor.permissions().containsAll(required)) throw new AuthFailure(403);
    }
    private void checkProof(Claims c,CurrentProof p,Set<String> required,Map<String,String> resource) {
        long now=clock.instant().getEpochSecond();
        if (p==null || !p.active() || p.validUntil()<=now || p.authorizationExpiresAt()<=now) throw new AuthFailure(503);
        if (!c.grantId().equals(p.grantId()) || !c.userId().equals(p.userId()) || !c.sid().equals(p.sid())
                || !c.userScope().equals(p.userScope()) || !c.actor().equals(p.actor()) || !c.audience().equals(p.audience()))
            throw new AuthFailure(401);
        if (required==null || resource==null || !c.resource().equals(resource) || !c.resource().equals(p.resource())
                || !c.permissions().containsAll(required) || !p.permissions().containsAll(c.permissions())
                || c.expiresAt()>p.authorizationExpiresAt()) throw new AuthFailure(403);
    }
    public static Set<String> parsePermissions(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size()>32) throw new IllegalArgumentException();
        var result=new LinkedHashSet<String>();
        for (var n:node) if (!n.isTextual() || n.textValue().length()>64 || !ServiceTokenVerifier.validPermission(n.textValue()) || !result.add(n.textValue())) throw new IllegalArgumentException();
        return Collections.unmodifiableSet(result);
    }
    public static Map<String,String> parseResource(JsonNode node,String purpose) {
        if (!node.isObject() || node.size()>RESOURCE_FIELDS.size()) throw new IllegalArgumentException();
        var values=new LinkedHashMap<String,String>();
        node.fields().forEachRemaining(e -> {
            if (!RESOURCE_FIELDS.contains(e.getKey()) || !e.getValue().isTextual()) throw new IllegalArgumentException();
            String value=e.getValue().textValue();
            if (e.getKey().equals("knowledgeBaseId")) {
                if (!validId(value)) throw new IllegalArgumentException();
            } else if (e.getKey().equals("controlEpoch")) {
                if (!validEpoch(value)) throw new IllegalArgumentException();
            } else if (!validOpaque(value)) throw new IllegalArgumentException();
            values.put(e.getKey(),value);
        });
        if (!values.containsKey("knowledgeBaseId") || !values.containsKey("clientRequestId")) throw new IllegalArgumentException();
        boolean create="CREATE".equals(purpose),checkpoint="SAFE_CHECKPOINT".equals(purpose);
        if (create && (values.containsKey("runId") || values.containsKey("controlEpoch") || values.containsKey("workerId"))
                || !create && !(values.containsKey("runId") && values.containsKey("controlEpoch") && values.containsKey("workerId"))
                || checkpoint && !(values.containsKey("stepId") && values.containsKey("checkpointOperationId"))
                || !checkpoint && (values.containsKey("stepId") || values.containsKey("checkpointOperationId"))) throw new IllegalArgumentException();
        return Collections.unmodifiableMap(values);
    }
    private static boolean singleAudience(JsonNode value,String expected) {
        return value.isTextual() && expected.equals(value.textValue())
                || value.isArray() && value.size()==1 && value.get(0).isTextual() && expected.equals(value.get(0).textValue());
    }
    public static boolean validOpaque(String value) {return value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");}
    public static boolean validEpoch(String value) {
        try {return value!=null && value.matches("0|[1-9][0-9]{0,18}") && Long.parseLong(value)>=0;}
        catch (NumberFormatException e) {return false;}
    }
    public static boolean validId(String value) {
        try {return value!=null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value)>0;}
        catch (NumberFormatException e) {return false;}
    }
    private static String text(JsonNode node,String name) {
        if (!node.path(name).isTextual()) throw new IllegalArgumentException();return node.path(name).textValue();
    }
}
