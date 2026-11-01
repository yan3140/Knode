package com.knode.user.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.*;
import com.knode.user.account.AccountService;
import com.knode.user.auth.*;
import io.jsonwebtoken.Jwts;
import java.time.*;
import java.util.*;

/** Coordinates locally stored lineage with fresh, remotely resolved domain authority. */
public final class DelegationService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final SigningKeyRing keys;private final Clock clock;private final DelegationPolicy policy;
    private final DelegationAuthorityClient authority;private final DelegationGrantStore grants;
    private final SessionStore.Reader sessions;private final AccountService accounts;private final RevocationCoordinator revocations;
    private final UserTokenVerifier users;private final ServiceTokenVerifier machines;private final DelegationTokenVerifier verifier;
    public record Issued(String accessToken,String tokenType,int expiresIn,String audience,List<String> permissions,String grantId) {}
    public record Validation(String userId,String sid,String userScope,String grantId,Map<String,String> resource,Set<String> permissions,long expiresAt) {}
    public record CheckpointValidation(String userId,String sid,String userScope,String grantId,String jti,Map<String,String> resource,long expiresAt) {}
    private record Identity(String userId,String sid,String scope,long sessionExpiry) {}
    public DelegationService(SigningKeyRing keys,Clock clock,DelegationPolicy policy,DelegationAuthorityClient authority,
                             DelegationGrantStore grants,SessionStore.Reader sessions,AccountService accounts,RevocationCoordinator revocations) {
        this.keys=keys;this.clock=clock;this.policy=policy;this.authority=authority;this.grants=grants;this.sessions=sessions;
        this.accounts=accounts;this.revocations=revocations;users=new UserTokenVerifier(keys.publicKeys(),clock);
        machines=new ServiceTokenVerifier(keys.publicKeys(),clock);verifier=new DelegationTokenVerifier(keys.publicKeys(),clock);
    }
    public Issued issue(ServiceTokenVerifier.Principal caller,JsonNode input) {
        try {ServicePolicy.fields(input,Set.of("subjectToken","parentToken","grantId","purpose","audience","permissions","proofRefs","expectedResource"));}
        catch (RuntimeException e) {throw bad();}
        String purpose=required(input,"purpose"),audience=required(input,"audience");
        if (!Set.of("CREATE","CONTINUE","EXECUTE","EXCHANGE","SAFE_CHECKPOINT").contains(purpose)
                || !ServiceTokenVerifier.validService(audience)) throw bad();
        int source=(input.has("subjectToken")?1:0)+(input.has("parentToken")?1:0)+(input.has("grantId")?1:0);
        if (source!=1) throw bad();
        Set<String> requested;
        try {requested=DelegationTokenVerifier.parsePermissions(input.path("permissions"));} catch (RuntimeException e) {throw bad();}
        if ("SAFE_CHECKPOINT".equals(purpose) && !requested.equals(Set.of("agent:checkpoint:write"))) throw bad();
        Map<String,String> refs=refs(input.path("proofRefs"),!"CREATE".equals(purpose));
        Map<String,String> expectation=Map.of();
        if (input.has("expectedResource")) {
            try {expectation=DelegationTokenVerifier.parseResource(input.get("expectedResource"),purpose);}
            catch (RuntimeException e) {throw bad();}
        }
        DelegationGrantStore.Grant parent=null;Identity identity;long parentTokenUntil=Long.MAX_VALUE;
        if (input.has("subjectToken")) {
            if (!Set.of("CREATE","CONTINUE").contains(purpose) || !"knode-knowledge".equals(caller.service()) || !"knode-agent".equals(audience))
                throw denied();
            String token=token(input,"subjectToken");var verified=users.verifyToken(token);
            identity=live(verified.userId(),verified.sid(),verified.scope());
            if (verified.expiresAt()>identity.sessionExpiry()) throw invalid();
            parentTokenUntil=verified.expiresAt();
        } else {
            if (Set.of("CREATE","CONTINUE").contains(purpose)) throw denied();
            if (input.has("parentToken")) {
                var verified=verifier.verifyNormal(token(input,"parentToken"),caller.service());
                parent=grants.verifiedLineage(verified.grantId());match(parent,verified);
                parentTokenUntil=verified.expiresAt();
            } else {
                if ("SAFE_CHECKPOINT".equals(purpose)) throw denied();
                parent=grants.verifiedLineage(required(input,"grantId"));
            }
            if (!caller.service().equals(parent.holder()) || "SAFE_CHECKPOINT".equals(parent.purpose())) throw invalid();
            transition(caller.service(),audience,purpose,parent.purpose());
            identity=live(parent.userId(),parent.sid(),parent.scope());
            var combined=new LinkedHashMap<>(parent.resource());
            for (var e:expectation.entrySet()) {
                String before=combined.putIfAbsent(e.getKey(),e.getValue());
                if (before!=null && !before.equals(e.getValue())) throw denied();
            }
            expectation=Map.copyOf(combined);
        }
        policy.allowed(caller.service(),audience,purpose);
        String parentId=parent==null?null:parent.id(),rootId=parent==null?null:parent.rootId();
        var subject=new DelegationAuthorityClient.Subject(identity.userId(),identity.sid(),identity.scope());
        var knowledge=authority.resolve("knode-knowledge",new DelegationAuthorityClient.Request(refs.get("knowledge"),"KNOWLEDGE_"+purpose,purpose,subject,caller.service(),parentId,rootId,expectation));
        DelegationAuthorityClient.Proof agent=null;
        if (!"CREATE".equals(purpose)) {
            agent=authority.resolve("knode-agent",new DelegationAuthorityClient.Request(refs.get("agent"),"AGENT_"+purpose,purpose,subject,caller.service(),parentId,rootId,knowledge.resource()));
            if (!agent.resource().equals(knowledge.resource())) throw unavailable();
        }
        if (parent!=null) {
            checkRevision(parent.proofRevisions().get("knowledge"),knowledge.sourceRevision());
            if (agent!=null && parent.proofRevisions().containsKey("agent")) checkRevision(parent.proofRevisions().get("agent"),agent.sourceRevision());
        }
        Map<String,Set<String>> envelope=intersect(parent==null?knowledge.permissionsByAudience():parent.envelope(),knowledge.permissionsByAudience(),agent==null?null:agent.permissionsByAudience());
        Set<String> allowed=new HashSet<>(envelope.getOrDefault(audience,Set.of()));allowed.retainAll(policy.allowed(caller.service(),audience,purpose));
        if (!allowed.containsAll(requested)) throw denied();
        long authorizedUntil=Math.min(parent==null?parentTokenUntil:parent.authorizedUntil(),knowledge.authorizationExpiresAt());
        if (agent!=null) authorizedUntil=Math.min(authorizedUntil,agent.authorizationExpiresAt());
        long now=clock.instant().getEpochSecond();
        long expiry=Math.min(now+("SAFE_CHECKPOINT".equals(purpose)?300:60),Math.min(caller.expiresAt(),Math.min(parentTokenUntil,Math.min(authorizedUntil,Math.min(identity.sessionExpiry(),knowledge.validUntil())))));
        if (agent!=null) expiry=Math.min(expiry,agent.validUntil());
        if (expiry<=now) throw invalid();
        // Re-read current authority after remote calls; no cached positive session state.
        var latest=live(identity.userId(),identity.sid(),identity.scope());
        expiry=Math.min(expiry,latest.sessionExpiry());
        if (expiry<=clock.instant().getEpochSecond()) throw invalid();
        String id=UUID.randomUUID().toString(),root=parent==null?id:parent.rootId(),jti=UUID.randomUUID().toString();
        String use="SAFE_CHECKPOINT".equals(purpose)?"SAFE_CHECKPOINT":"NORMAL";
        String jwt=Jwts.builder().header().keyId(keys.activeKid()).and().issuer("knode-user").subject(identity.userId())
                .audience().add(audience).and().claim("token_use","delegation").claim("sid",identity.sid()).claim("user_scope",identity.scope())
                .claim("act",Map.of("sub",caller.service())).claim("grantId",id).claim("purpose",purpose).claim("delegation_use",use)
                .claim("permissions",List.copyOf(requested)).claim("resource",knowledge.resource()).id(jti)
                .issuedAt(Date.from(Instant.ofEpochSecond(now))).expiration(Date.from(Instant.ofEpochSecond(expiry))).signWith(keys.privateKey(),Jwts.SIG.RS256).compact();
        if (jwt.length()>DelegationTokenVerifier.MAX_TOKEN_LENGTH) throw new InternalFailure(400,"TOKEN_BUDGET_EXCEEDED");
        if ("SAFE_CHECKPOINT".equals(purpose)) verifier.verifyCheckpoint(jwt,audience); else verifier.verifyNormal(jwt,audience);
        var versions=new LinkedHashMap<String,String>();versions.put("knowledge",knowledge.sourceRevision());if (agent!=null) versions.put("agent",agent.sourceRevision());
        grants.insert(new DelegationGrantStore.Grant(id,root,parentId,identity.userId(),identity.sid(),identity.scope(),caller.service(),audience,purpose,
                requested,knowledge.resource(),envelope,refs,versions,authorizedUntil));
        return new Issued(jwt,"Bearer",(int)(expiry-now),audience,List.copyOf(requested),id);
    }
    public Validation validate(ServiceTokenVerifier.Principal receiver,JsonNode input) {
        try {ServicePolicy.fields(input,Set.of("actorToken","delegationToken","permission","resource"));}
        catch (RuntimeException e) {throw bad();}
        String permission=required(input,"permission");
        if (!ServiceTokenVerifier.validPermission(permission) || permission.length()>64) throw bad();
        ServiceTokenVerifier.Principal actor=machines.verify(token(input,"actorToken"),receiver.service(),Set.of(permission));
        var verified=verifier.verifyNormal(token(input,"delegationToken"),receiver.service());
        var grant=grants.verifiedLineage(verified.grantId());match(grant,verified);
        var resource=requestedResource(input,verified.purpose());
        Identity identity=live(grant.userId(),grant.sid(),grant.scope());
        var proof=currentProof(grant);
        try {
            var stored=sessions.find(grant.sid()).orElseThrow(DelegationService::invalid);
            verifier.authorize(verified,actor,Optional.of(convert(stored)),proof,Set.of(permission),resource);
        } catch (SessionStore.Unavailable e) {throw unavailable();}
        live(identity.userId(),identity.sid(),identity.scope());
        return new Validation(verified.userId(),verified.sid(),verified.userScope(),verified.grantId(),verified.resource(),verified.permissions(),verified.expiresAt());
    }
    public CheckpointValidation validateCheckpoint(ServiceTokenVerifier.Principal receiver,JsonNode input) {
        try {ServicePolicy.fields(input,Set.of("actorToken","delegationToken","resource"));}
        catch (RuntimeException e) {throw bad();}
        String permission="agent:checkpoint:write";
        ServiceTokenVerifier.Principal actor=machines.verify(token(input,"actorToken"),receiver.service(),Set.of(permission));
        var verified=verifier.verifyCheckpoint(token(input,"delegationToken"),receiver.service());
        var grant=grants.verifiedLineage(verified.grantId());match(grant,verified);
        var resource=requestedResource(input,"SAFE_CHECKPOINT");
        verifier.authorizeCheckpoint(verified,actor,currentProof(grant),resource);
        return new CheckpointValidation(verified.userId(),verified.sid(),verified.userScope(),verified.grantId(),verified.jti(),verified.resource(),verified.expiresAt());
    }
    private DelegationTokenVerifier.CurrentProof currentProof(DelegationGrantStore.Grant grant) {
        var subject=new DelegationAuthorityClient.Subject(grant.userId(),grant.sid(),grant.scope());
        String expectedRoot=grant.parentId()==null?null:grant.rootId();
        var know=authority.resolve("knode-knowledge",new DelegationAuthorityClient.Request(grant.proofRefs().get("knowledge"),"KNOWLEDGE_"+grant.purpose(),grant.purpose(),subject,grant.actor(),grant.parentId(),expectedRoot,grant.resource()));
        DelegationAuthorityClient.Proof agent=null;
        if (!"CREATE".equals(grant.purpose())) {
            agent=authority.resolve("knode-agent",new DelegationAuthorityClient.Request(grant.proofRefs().get("agent"),"AGENT_"+grant.purpose(),grant.purpose(),subject,grant.actor(),grant.parentId(),expectedRoot,grant.resource()));
            if (!agent.resource().equals(know.resource())) throw unavailable();
        }
        checkRevision(grant.proofRevisions().get("knowledge"),know.sourceRevision());
        if (agent!=null) checkRevision(grant.proofRevisions().get("agent"),agent.sourceRevision());
        var available=intersect(grant.envelope(),know.permissionsByAudience(),agent==null?null:agent.permissionsByAudience());
        var permissions=new HashSet<>(available.getOrDefault(grant.holder(),Set.of()));permissions.retainAll(policy.allowed(grant.actor(),grant.holder(),grant.purpose()));
        long valid=Math.min(know.validUntil(),agent==null?Long.MAX_VALUE:agent.validUntil());
        long auth=Math.min(grant.authorizedUntil(),Math.min(know.authorizationExpiresAt(),agent==null?Long.MAX_VALUE:agent.authorizationExpiresAt()));
        return new DelegationTokenVerifier.CurrentProof(grant.id(),grant.userId(),grant.sid(),grant.scope(),grant.actor(),grant.holder(),know.resource(),permissions,valid,auth,true);
    }
    private Identity live(String user,String sid,String scope) {
        try {
            long id=Long.parseLong(user);var s=sessions.find(sid).orElseThrow(DelegationService::invalid);
            if (s.schemaVersion()!=1 || !sid.equals(s.sid()) || !user.equals(s.userId()) || !scope.equals(s.scope())
                    || !"ACTIVE".equals(s.status()) || s.expiresAt()<=clock.instant().getEpochSecond()) throw invalid();
            if (revocations.pending(sid)) throw unavailable();
            accounts.active(id,scope);
            if (!accounts.sessionActive(sid,id,scope)) throw invalid();
            return new Identity(user,sid,scope,s.expiresAt());
        } catch (SessionStore.Unavailable e) {throw unavailable();}
        catch (AuthenticationFailure e) {throw new InternalFailure(e.status(),e.status()==503?"DELEGATION_UNAVAILABLE":e.status()==403?"DELEGATION_DENIED":"INVALID_DELEGATION");}
    }
    private static Session convert(SessionStore.Session s) {return new Session(s.schemaVersion(),s.sid(),s.userId(),s.scope(),s.status(),s.expiresAt());}
    private static void transition(String actor,String audience,String purpose,String parentPurpose) {
        if (!"knode-agent".equals(actor)) throw denied();
        switch (purpose) {
            case "EXECUTE" -> {if (!"knode-agent".equals(audience) || !Set.of("CREATE","CONTINUE").contains(parentPurpose)) throw denied();}
            case "EXCHANGE" -> {if (!"knode-knowledge".equals(audience) || !Set.of("CREATE","CONTINUE","EXECUTE").contains(parentPurpose)) throw denied();}
            case "SAFE_CHECKPOINT" -> {if (!"knode-agent".equals(audience) || !Set.of("EXECUTE","CONTINUE").contains(parentPurpose)) throw denied();}
            default -> throw denied();
        }
    }
    private static void checkRevision(String previous,String current) {
        if (!DelegationTokenVerifier.validId(previous) || !DelegationTokenVerifier.validId(current)
                || Long.parseLong(current)<Long.parseLong(previous)) throw unavailable();
    }
    private static Map<String,Set<String>> intersect(Map<String,Set<String>> root,Map<String,Set<String>> knowledge,Map<String,Set<String>> agent) {
        var result=new LinkedHashMap<String,Set<String>>();
        root.forEach((aud,p) -> {
            var values=new HashSet<>(p);values.retainAll(knowledge.getOrDefault(aud,Set.of()));
            if (agent!=null) values.retainAll(agent.getOrDefault(aud,Set.of()));
            if (!values.isEmpty()) result.put(aud,Set.copyOf(values));
        });
        return Map.copyOf(result);
    }
    private static void match(DelegationGrantStore.Grant g,DelegationTokenVerifier.Verified v) {
        if (!g.id().equals(v.grantId()) || !g.userId().equals(v.userId()) || !g.sid().equals(v.sid()) || !g.scope().equals(v.userScope())
                || !g.actor().equals(v.actor()) || !g.holder().equals(v.audience()) || !g.purpose().equals(v.purpose())
                || !g.resource().equals(v.resource()) || !g.permissions().equals(v.permissions()) || v.expiresAt()>g.authorizedUntil()) throw invalid();
    }
    private static void match(DelegationGrantStore.Grant g,DelegationTokenVerifier.VerifiedCheckpoint v) {
        if (!g.id().equals(v.grantId()) || !g.userId().equals(v.userId()) || !g.sid().equals(v.sid()) || !g.scope().equals(v.userScope())
                || !g.actor().equals(v.actor()) || !g.holder().equals(v.audience()) || !"SAFE_CHECKPOINT".equals(g.purpose())
                || !g.resource().equals(v.resource()) || !g.permissions().equals(Set.of("agent:checkpoint:write")) || v.expiresAt()>g.authorizedUntil()) throw invalid();
    }
    private static Map<String,String> requestedResource(JsonNode input,String purpose) {
        try {return DelegationTokenVerifier.parseResource(input.path("resource"),purpose);} catch (RuntimeException e) {throw bad();}
    }
    private static Map<String,String> refs(JsonNode node,boolean needAgent) {
        try {
            ServicePolicy.fields(node,Set.of("knowledge","agent"));
            String knowledge=ServicePolicy.text(node,"knowledge");
            if (!DelegationTokenVerifier.validOpaque(knowledge)) throw new IllegalArgumentException();
            if (!needAgent && node.has("agent")) throw new IllegalArgumentException();
            if (!needAgent) return Map.of("knowledge",knowledge);
            String agent=ServicePolicy.text(node,"agent");if (!DelegationTokenVerifier.validOpaque(agent)) throw new IllegalArgumentException();
            return Map.of("knowledge",knowledge,"agent",agent);
        } catch (Exception e) {throw bad();}
    }
    private static String required(JsonNode root,String key) {
        try {return ServicePolicy.text(root,key);} catch (Exception e) {throw bad();}
    }
    private static String token(JsonNode root,String key) {
        String value=required(root,key);if (value.length()>8192) throw bad();return value;
    }
    private static InternalFailure bad(){return new InternalFailure(400,"INVALID_ARGUMENT");}
    private static InternalFailure invalid(){return new InternalFailure(401,"INVALID_DELEGATION");}
    private static InternalFailure denied(){return new InternalFailure(403,"DELEGATION_DENIED");}
    private static InternalFailure unavailable(){return new InternalFailure(503,"DELEGATION_UNAVAILABLE");}
}
