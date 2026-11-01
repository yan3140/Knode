package com.knode.user.config;

import com.knode.user.auth.*;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.HashMap;
import java.util.Map;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class UserConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean(name="revocationScheduler") ThreadPoolTaskScheduler revocationScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("auth-revocation-");
        scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }
    @Bean(name="sessionEventScheduler") ThreadPoolTaskScheduler sessionEventScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("session-events-");
        scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }
    @Bean(destroyMethod="close") SessionEventTransport sessionEventTransport(Environment env) {
        String enabled=env.getProperty("KNODE_SESSION_EVENTS_ENABLED","false");
        if ("false".equals(enabled)) return new DisabledSessionEventTransport();
        if ("true".equals(enabled)) return new RedisSessionEventTransport(env);
        throw new IllegalArgumentException("Invalid session events enabled flag");
    }
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    private Map<String,String> settings(Environment env, String prefix) {
        var result=new HashMap<String,String>();
        for (String suffix : new String[]{"HOST","PORT","USERNAME","PASSWORD","DATABASE","TLS"}) {
            String value=env.getProperty(prefix+suffix);
            if (value!=null) result.put(prefix+suffix,value);
        }
        return result;
    }
    @Bean(name="authReadConnection", destroyMethod="destroy")
    LettuceConnectionFactory readConnection(Environment env) {
        String p="KNODE_AUTH_REDIS_READ_"; return SessionRedisConnections.create(settings(env,p),p);
    }
    @Bean(name="authWriteConnection", destroyMethod="destroy")
    LettuceConnectionFactory writeConnection(Environment env) {
        String p="KNODE_AUTH_REDIS_WRITE_"; return SessionRedisConnections.create(settings(env,p),p);
    }
    @Bean SessionStore.Reader sessionReader(@Qualifier("authReadConnection") LettuceConnectionFactory connection, Environment env) {
        return new RedisSessionReader(new StringRedisTemplate(connection),env.getRequiredProperty("KNODE_AUTH_ENVIRONMENT"));
    }
    @Bean SessionStore.Writer sessionWriter(@Qualifier("authWriteConnection") LettuceConnectionFactory connection, Environment env, Clock clock) {
        return new RedisSessionWriter(new StringRedisTemplate(connection),env.getRequiredProperty("KNODE_AUTH_ENVIRONMENT"),clock);
    }
    @Bean SigningKeyRing signingKeys(Environment env) {
        String kid=env.getRequiredProperty("KNODE_AUTH_SIGNING_KID");
        String privateFile=env.getProperty("KNODE_AUTH_SIGNING_PRIVATE_FILE");
        String privateEnvironment=env.getProperty("KNODE_AUTH_SIGNING_PRIVATE_ENV");
        if ((privateFile==null)==(privateEnvironment==null)) throw new IllegalStateException("Set exactly one private key source");
        RSAPrivateKey privateKey=privateFile!=null ? KeyMaterial.privateFromFile(Path.of(privateFile)) : KeyMaterial.privateFromEnvironment(privateEnvironment);
        String trustFile=env.getProperty("KNODE_AUTH_TRUSTED_PUBLIC_KEYS_FILE");
        var trusted=trustFile==null ? Map.of(kid,KeyMaterial.publicFromFile(Path.of(env.getRequiredProperty("KNODE_AUTH_SIGNING_PUBLIC_FILE")))) : SigningKeyRing.readTrustedFile(Path.of(trustFile));
        return new SigningKeyRing(kid,privateKey,trusted);
    }
    @Bean UserTokens userTokens(SigningKeyRing keys,Clock clock,SessionStore.Reader reader,SessionStore.Writer writer) {
        return new UserTokens(keys.privateKey(),keys.activeKid(),keys.publicKeys(),clock,reader,writer);
    }
    @Bean com.knode.user.internal.ServicePolicy servicePolicy(Environment env,BCryptPasswordEncoder bcrypt) {
        String file=env.getProperty("KNODE_AUTH_SERVICE_POLICY_FILE"), variable=env.getProperty("KNODE_AUTH_SERVICE_POLICY_ENV");
        if (file!=null && variable!=null) throw new IllegalStateException("Set only one service policy source");
        if (file==null && variable==null) return com.knode.user.internal.ServicePolicy.disabled(bcrypt);
        try {
            String content=file!=null ? java.nio.file.Files.readString(Path.of(file)) : System.getenv(variable);
            if (content==null) throw new IllegalStateException("Service policy environment variable missing");
            return com.knode.user.internal.ServicePolicy.load(content,bcrypt);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read service policy"); }
    }
    @Bean com.knode.user.internal.ServiceTokens serviceTokens(SigningKeyRing keys,com.knode.user.internal.ServicePolicy policy,Clock clock) {
        return new com.knode.user.internal.ServiceTokens(keys,policy,clock);
    }
    @Bean com.knode.user.internal.DelegationPolicy delegationPolicy(Environment env) {
        String file=env.getProperty("KNODE_DELEGATION_POLICY_FILE"),variable=env.getProperty("KNODE_DELEGATION_POLICY_ENV");
        if (file!=null && variable!=null) throw new IllegalStateException("Set one delegation policy source");
        if (file==null && variable==null) return com.knode.user.internal.DelegationPolicy.disabled();
        try {
            String content=file!=null?java.nio.file.Files.readString(Path.of(file)):System.getenv(variable);
            if (content==null) throw new IllegalStateException("Delegation policy environment variable missing");
            return com.knode.user.internal.DelegationPolicy.load(content);
        } catch (java.io.IOException e) {throw new IllegalStateException("Cannot read delegation policy");}
    }
    @Bean(destroyMethod="close") com.knode.user.internal.DelegationAuthorityClient delegationAuthority(Environment env,com.knode.user.internal.ServiceTokens tokens,Clock clock,
            org.springframework.beans.factory.ObjectProvider<com.knode.governance.GovernedCall> governed,
            org.springframework.beans.factory.ObjectProvider<io.opentelemetry.api.OpenTelemetry> telemetry) {
        var origins=new HashMap<String,java.net.URI>();
        String knowledge=env.getProperty("KNODE_DELEGATION_KNOWLEDGE_ORIGIN"),agent=env.getProperty("KNODE_DELEGATION_AGENT_ORIGIN");
        try {
            if (knowledge!=null) origins.put("knode-knowledge",java.net.URI.create(knowledge));
            if (agent!=null) origins.put("knode-agent",java.net.URI.create(agent));
        } catch (IllegalArgumentException e) {throw new IllegalArgumentException("Invalid delegation authority origin");}
        String file=env.getProperty("KNODE_DELEGATION_OUTBOUND_SECRET_FILE"),variable=env.getProperty("KNODE_DELEGATION_OUTBOUND_SECRET_ENV");
        if (file!=null && variable!=null) throw new IllegalStateException("Set one delegation outbound secret source");
        return new com.knode.user.internal.DelegationAuthorityClient(origins,audience -> {
            try {
                String secret=file!=null?java.nio.file.Files.readString(Path.of(file)):variable!=null?System.getenv(variable):null;
                if (secret==null || secret.isEmpty() || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
                    throw new IllegalStateException("Missing external outbound credential");
                return tokens.issue("knode-user",secret,audience,java.util.List.of("auth:proofs:read")).accessToken();
            } catch (java.io.IOException e) {throw new IllegalStateException("Cannot read outbound credential");}
        },clock,java.time.Duration.ofSeconds(2),governed.getIfAvailable(),
                env.getProperty("knode.observability.enabled",Boolean.class,false)?telemetry.getIfAvailable():null);
    }
    @Bean com.knode.user.internal.DelegationGrantStore delegationGrantStore(org.springframework.jdbc.core.JdbcTemplate db,org.springframework.transaction.support.TransactionTemplate tx,Clock clock) {
        return new com.knode.user.internal.DelegationGrantStore(db,tx,clock);
    }
    @Bean com.knode.user.internal.DelegationService delegationService(SigningKeyRing keys,Clock clock,com.knode.user.internal.DelegationPolicy policy,
            com.knode.user.internal.DelegationAuthorityClient authority,com.knode.user.internal.DelegationGrantStore grants,
            SessionStore.Reader sessions,com.knode.user.account.AccountService accounts,RevocationCoordinator revocations) {
        return new com.knode.user.internal.DelegationService(keys,clock,policy,authority,grants,sessions,accounts,revocations);
    }
}
