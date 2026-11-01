package com.knode.user.web;

import com.knode.user.auth.AuthenticationFailure;
import com.knode.user.auth.SessionStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final SessionStore.Reader sessions;
    private final SessionStore.Writer writer;
    private final TransactionTemplate tx;
    private final com.knode.user.internal.ServiceTokens serviceTokens;
    private final com.knode.user.auth.SessionEventRelay sessionEvents;
    private final com.knode.user.internal.DelegationPolicy delegationPolicy;
    private final com.knode.user.internal.DelegationAuthorityClient authority;
    private final org.springframework.beans.factory.ObjectProvider<com.knode.governance.GovernanceRules> governance;
    private final com.knode.user.config.ControlledRelaySettings relaySettings;
    private final com.knode.user.config.UserDiscoveryStatus discovery;
    public HealthController(JdbcTemplate jdbc,SessionStore.Reader sessions,SessionStore.Writer writer,TransactionTemplate tx,
            com.knode.user.internal.ServiceTokens serviceTokens,com.knode.user.auth.SessionEventRelay sessionEvents,
            com.knode.user.internal.DelegationPolicy delegationPolicy,com.knode.user.internal.DelegationAuthorityClient authority,
            org.springframework.beans.factory.ObjectProvider<com.knode.governance.GovernanceRules> governance,
            com.knode.user.config.ControlledRelaySettings relaySettings,com.knode.user.config.UserDiscoveryStatus discovery) {
        this.jdbc=jdbc;this.sessions=sessions;this.writer=writer;this.tx=tx;this.serviceTokens=serviceTokens;this.sessionEvents=sessionEvents;
        this.delegationPolicy=delegationPolicy;this.authority=authority;this.governance=governance;this.relaySettings=relaySettings;this.discovery=discovery;
    }
    @GetMapping("/health/live") public Map<String,String> live() { return Map.of("status","UP"); }
    @GetMapping("/health/ready") public Map<String,String> ready() {
        String sid=UUID.randomUUID().toString();
        for (String table:new String[]{"sys_user","sys_role","yan_menu","sys_user_role","sys_role_menu","auth_login_session","auth_revocation_outbox","user_security_lock"})
            jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE 1=0",Integer.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_security_lock WHERE id=1",Integer.class)!=1) throw new AuthenticationFailure(503);
        jdbc.queryForList("SELECT reason,event_schema_version,traceparent FROM auth_revocation_intent WHERE 1=0");
        jdbc.queryForList("SELECT event_json,reason,attempts,next_attempt_at,claim_token,claim_until,last_error,published_at FROM auth_revocation_outbox WHERE 1=0");
        jdbc.queryForList("SELECT event_id,actor_id,requested_at FROM auth_revocation_replay_audit WHERE 1=0");
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES(?,0,'blog',?,'PENDING')",sid,UUID.randomUUID().toString());
            jdbc.update("UPDATE auth_revocation_intent SET attempts=attempts WHERE sid=?",sid);
            for (String table:new String[]{"sys_user","sys_role","yan_menu","auth_login_session"})
                jdbc.update("UPDATE "+table+" SET "+("auth_login_session".equals(table)?"active=active":"status=status")+" WHERE 1=0");
            status.setRollbackOnly();
        });
        try { sessions.find(sid); writer.probe(); }
        catch (SessionStore.Unavailable e) { throw new AuthenticationFailure(503); }
        if (sessionEvents.enabled() && !sessionEvents.probe()) throw new AuthenticationFailure(503);
        var rules=governance.getIfAvailable();
        if (rules!=null && !org.springframework.boot.actuate.health.Status.UP.equals(rules.health().getStatus())) throw new AuthenticationFailure(503);
        if (delegationPolicy.enabled()) {
            jdbc.queryForList("SELECT grant_id,root_grant_id,parent_grant_id,state,authorization_expires_at FROM auth_delegation_grant WHERE 1=0");
            jdbc.queryForList("SELECT grant_id,actor_service,action FROM auth_delegation_grant_audit WHERE 1=0");
            if (!authority.configured() || !authority.credentialsReady()) throw new AuthenticationFailure(503);
        }
        if (!org.springframework.boot.actuate.health.Status.UP.equals(discovery.health().getStatus())) throw new AuthenticationFailure(503);
        if (!org.springframework.boot.actuate.health.Status.UP.equals(relaySettings.health().getStatus())) throw new AuthenticationFailure(503);
        return Map.of("status","UP","serviceIssuer",serviceTokens.enabled()?"ENABLED":"DISABLED",
                "sessionEvents",sessionEvents.enabled()?"PROBE_OK_TARGET_WRITE_UNVERIFIED":"DISABLED",
                "governanceRules",rules==null?"DISABLED":"UP",
                "delegationAuthority",delegationPolicy.enabled()?"CONFIGURED_HEALTH_UNVERIFIED":"DISABLED",
                "userNacosConfig",relaySettings.enabled()?"UP":"DISABLED",
                "userDiscovery",discovery.health().getDetails().getOrDefault("status","UP").toString());
    }
}
