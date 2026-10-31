package com.knode.user;

import com.knode.user.auth.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in; only an empty local test database and newly generated verify-prefix keys are touched. */
@EnabledIfSystemProperty(named="knode.it.real.relay.enabled",matches="true")
class ExternalRelayIT {
    private static String env(String name) {
        String v=System.getenv(name);if (v==null || v.isBlank()) throw new IllegalArgumentException("Missing "+name);return v;
    }
    @Test void actualXaddAndDatabaseAckUseOnlyExplicitVerifyPrefix() throws Exception {
        String environment=env("KNODE_IT_AUTH_ENVIRONMENT"), url=env("KNODE_IT_DB_URL");
        ExternalStackGuard.check(environment,url);
        String dbUser=env("KNODE_IT_DB_USERNAME"),dbPassword=env("KNODE_IT_DB_PASSWORD");
        String host=env("KNODE_IT_REDIS_HOST"),port=env("KNODE_IT_REDIS_PORT"),database=env("KNODE_IT_REDIS_DATABASE"),tls=env("KNODE_IT_REDIS_TLS");
        String eventUser=env("KNODE_IT_EVENT_REDIS_USERNAME"),eventPassword=env("KNODE_IT_EVENT_REDIS_PASSWORD");
        String readUser=env("KNODE_IT_REDIS_READ_USERNAME"),writeUser=env("KNODE_IT_REDIS_WRITE_USERNAME");
        String prefix=env("KNODE_IT_EVENT_STREAM_PREFIX"),allowed="knode:"+environment+":v1:business:";
        if (!prefix.startsWith(allowed) || !prefix.matches("[A-Za-z0-9:_-]{1,180}") || !prefix.endsWith(":"))
            throw new IllegalArgumentException("Explicit verify business prefix required");
        if (!tls.equals("true") && !tls.equals("false")) throw new IllegalArgumentException("Explicit Redis TLS flag required");
        String stream=prefix+"relay-"+UUID.randomUUID(),probe=prefix+"probe-"+UUID.randomUUID();
        var ds=new DriverManagerDataSource(url,dbUser,dbPassword);
        try (Connection c=ds.getConnection();Statement statement=c.createStatement();
             ResultSet rs=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            if (!rs.next() || rs.getInt(1)!=0) throw new IllegalStateException("Dedicated test schema must be empty");
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(ds);
        var e=new MockEnvironment().withProperty("KNODE_AUTH_ENVIRONMENT",environment)
                .withProperty("KNODE_SESSION_EVENTS_REDIS_HOST",host).withProperty("KNODE_SESSION_EVENTS_REDIS_PORT",port)
                .withProperty("KNODE_SESSION_EVENTS_REDIS_DATABASE",database).withProperty("KNODE_SESSION_EVENTS_REDIS_TLS",tls)
                .withProperty("KNODE_SESSION_EVENTS_REDIS_USERNAME",eventUser).withProperty("KNODE_SESSION_EVENTS_REDIS_PASSWORD",eventPassword)
                .withProperty("KNODE_AUTH_REDIS_READ_USERNAME",readUser).withProperty("KNODE_AUTH_REDIS_WRITE_USERNAME",writeUser)
                .withProperty("KNODE_SESSION_EVENTS_STREAM_KEY",stream).withProperty("KNODE_SESSION_EVENTS_PROBE_PREFIX",probe);
        var cfg=new RedisStandaloneConfiguration(host,Integer.parseInt(port));
        cfg.setDatabase(Integer.parseInt(database));cfg.setUsername(eventUser);cfg.setPassword(RedisPassword.of(eventPassword));
        var builder=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2));if ("true".equals(tls)) builder.useSsl();
        var reader=new LettuceConnectionFactory(cfg,builder.build());reader.afterPropertiesSet();reader.start();
        var redis=new StringRedisTemplate(reader);RedisSessionEventTransport transport=null;
        try {
            transport=new RedisSessionEventTransport(e);
            var targetTransport=transport;
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(targetTransport::probe);var second=pool.submit(targetTransport::probe);
                assertTrue(first.get(15,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            } finally {pool.shutdownNow();}
            var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            String sid=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();Instant now=Instant.now();
            String payload=SessionEventEnvelope.encode(id,sid,42,"blog","LOGOUT",now);
            jdbc.update("INSERT INTO auth_revocation_outbox(event_id,sid,user_id,scope,occurred_at,execution_scope,state,reason,event_json) VALUES(?,?,42,'blog',?,'SID_CURRENT_EXECUTIONS','PENDING','LOGOUT',?)",id,sid,Timestamp.from(now),payload);
            var relay=new SessionEventRelay(jdbc,tx,transport,Clock.systemUTC(),e);
            assertEquals(1,relay.relay(10));
            var records=redis.opsForStream().range(stream,Range.unbounded());
            assertNotNull(records);assertEquals(1,records.size());
            assertEquals(Map.of("event",payload),records.get(0).getValue());
            assertEquals("PUBLISHED",jdbc.queryForObject("SELECT state FROM auth_revocation_outbox WHERE event_id=?",String.class,id));
        } finally {
            try {redis.delete(stream);} finally {if (transport!=null) transport.close();reader.destroy();}
        }
    }
}
