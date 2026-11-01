package com.knode.user.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.UserTokenVerifier;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Identity-owned immutable lineage and grants, never the authority's business state. */
public final class DelegationGrantStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Clock clock;
    public record Grant(String id,String rootId,String parentId,String userId,String sid,String scope,String actor,
                        String holder,String purpose,Set<String> permissions,Map<String,String> resource,
                        Map<String,Set<String>> envelope,Map<String,String> proofRefs,Map<String,String> proofRevisions,long authorizedUntil) {}
    public DelegationGrantStore(JdbcTemplate db,TransactionTemplate tx,Clock clock) {this.db=db;this.tx=tx;this.clock=clock;}
    public Grant get(String id) {
        if (!UserTokenVerifier.validUuid(id)) throw new InternalFailure(400,"INVALID_ARGUMENT");
        var rows=db.queryForList("SELECT * FROM auth_delegation_grant WHERE grant_id=?",id);
        if (rows.size()!=1) throw new InternalFailure(401,"INVALID_DELEGATION");
        try {
            var r=rows.get(0);String stored=(String)r.get("grant_id");
            if (!id.equals(stored) || !"ACTIVE".equals(r.get("state"))) throw new IllegalArgumentException();
            long until=((Timestamp)r.get("authorization_expires_at")).toInstant().getEpochSecond();
            if (until<=clock.instant().getEpochSecond()) throw new IllegalArgumentException();
            return new Grant(stored,(String)r.get("root_grant_id"),(String)r.get("parent_grant_id"),Long.toString(((Number)r.get("user_id")).longValue()),
                    (String)r.get("sid"),(String)r.get("user_scope"),(String)r.get("actor_service"),(String)r.get("holder_service"),(String)r.get("purpose"),
                    JSON.readValue((String)r.get("permissions_json"),new TypeReference<Set<String>>(){}),
                    JSON.readValue((String)r.get("resource_json"),new TypeReference<Map<String,String>>(){}),
                    JSON.readValue((String)r.get("envelope_json"),new TypeReference<Map<String,Set<String>>>(){}),
                    JSON.readValue((String)r.get("proof_refs_json"),new TypeReference<Map<String,String>>(){}),
                    JSON.readValue((String)r.get("proof_revisions_json"),new TypeReference<Map<String,String>>(){}),until);
        } catch (Exception e) {throw new InternalFailure(401,"INVALID_DELEGATION");}
    }
    public Grant verifiedLineage(String id) {
        Grant current=get(id),child=current;Set<String> seen=new HashSet<>();int depth=0;
        while (true) {
            if (!seen.add(child.id()) || ++depth>3 || !child.rootId().equals(current.rootId())
                    || !child.userId().equals(current.userId()) || !child.sid().equals(current.sid()) || !child.scope().equals(current.scope()))
                throw new InternalFailure(401,"INVALID_DELEGATION");
            if (child.parentId()==null) {
                if (!child.id().equals(child.rootId())) throw new InternalFailure(401,"INVALID_DELEGATION");break;
            }
            Grant parent=get(child.parentId());
            if (child.authorizedUntil()>parent.authorizedUntil() || !parent.holder().equals(child.actor()))
                throw new InternalFailure(401,"INVALID_DELEGATION");
            child=parent;
        }
        return current;
    }
    public void insert(Grant grant) {
        try {
            String permissions=JSON.writeValueAsString(grant.permissions()),resource=JSON.writeValueAsString(grant.resource()),
                    envelope=JSON.writeValueAsString(grant.envelope()),refs=JSON.writeValueAsString(grant.proofRefs()),versions=JSON.writeValueAsString(grant.proofRevisions());
            tx.executeWithoutResult(status -> {
                db.update("INSERT INTO auth_delegation_grant(grant_id,root_grant_id,parent_grant_id,user_id,sid,user_scope,actor_service,holder_service,purpose,permissions_json,resource_json,envelope_json,proof_refs_json,proof_revisions_json,authorization_expires_at,state,checkpoint_operation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",
                        grant.id(),grant.rootId(),grant.parentId(),Long.parseLong(grant.userId()),grant.sid(),grant.scope(),grant.actor(),grant.holder(),grant.purpose(),
                        permissions,resource,envelope,refs,versions,Timestamp.from(Instant.ofEpochSecond(grant.authorizedUntil())),
                        "SAFE_CHECKPOINT".equals(grant.purpose())?grant.resource().get("checkpointOperationId"):null);
                db.update("INSERT INTO auth_delegation_grant_audit(grant_id,actor_service,audience,purpose,action) VALUES(?,?,?,?,'ISSUE')",
                        grant.id(),grant.actor(),grant.holder(),grant.purpose());
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {throw new InternalFailure(403,"DELEGATION_DENIED");}
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {throw new InternalFailure(503,"DELEGATION_UNAVAILABLE");}
    }
}
