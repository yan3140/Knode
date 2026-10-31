package com.knode.user;

import com.knode.user.auth.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulingIsolationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("revocationScheduler") ThreadPoolTaskScheduler revocationScheduler;
    @Autowired @Qualifier("sessionEventScheduler") ThreadPoolTaskScheduler eventScheduler;
    @MockitoBean SessionStore.Reader reader;
    @MockitoBean SessionStore.Writer writer;
    @MockitoBean SessionEventTransport eventTransport;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        UserHttpIntegrationTest.properties(registry);
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:schedule_isolation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "100");
        registry.add("KNODE_AUTH_REVOCATION_POLL_MS",() -> "100");
        registry.add("KNODE_SESSION_EVENTS_INITIAL_MS",() -> "100");
        registry.add("KNODE_SESSION_EVENTS_POLL_MS",() -> "100");
    }
    @BeforeAll void schema() {new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);}

    @Test void blockedEventAppendCannotDelayScheduledSessionRevocation() throws Exception {
        assertEquals(1,revocationScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertEquals(1,eventScheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertNotSame(revocationScheduler,eventScheduler);
        var appendEntered=new CountDownLatch(1);var releaseAppend=new CountDownLatch(1);
        var revoked=new CountDownLatch(1);var eventThread=new AtomicReference<String>();var authThread=new AtomicReference<String>();
        String sid=UUID.randomUUID().toString(),event=UUID.randomUUID().toString(),eventSid=UUID.randomUUID().toString();
        doAnswer(inv -> {
            eventThread.set(Thread.currentThread().getName());appendEntered.countDown();
            if (!releaseAppend.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("test append timeout");
            return null;
        }).when(eventTransport).publish(anyString());
        doAnswer(inv -> {authThread.set(Thread.currentThread().getName());revoked.countDown();return null;}).when(writer).revoke(sid);
        try {
            when(eventTransport.enabled()).thenReturn(true);
            Timestamp now=Timestamp.from(Instant.now());String payload=SessionEventEnvelope.encode(event,eventSid,42,"blog","LOGOUT",now.toInstant());
            jdbc.update("INSERT INTO auth_revocation_outbox(event_id,sid,user_id,scope,occurred_at,execution_scope,state,reason,event_json) VALUES(?,?,42,'blog',?,'SID_CURRENT_EXECUTIONS','PENDING','LOGOUT',?)",event,eventSid,now,payload);
            assertTrue(appendEntered.await(15,TimeUnit.SECONDS),"event scheduler did not start");
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT state FROM auth_revocation_outbox WHERE event_id=?",String.class,event));
            jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES(?,42,'blog',?,'PENDING')",sid,UUID.randomUUID().toString());
            assertTrue(revoked.await(5,TimeUnit.SECONDS),"sid recovery was blocked by event delivery");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime()<deadline && !"REVOKED".equals(jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sid))) Thread.sleep(50);
            assertEquals("REVOKED",jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sid));
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT state FROM auth_revocation_outbox WHERE event_id=?",String.class,event));
            assertEquals(1,releaseAppend.getCount());
            assertTrue(eventThread.get().startsWith("session-events-"));
            assertTrue(authThread.get().startsWith("auth-revocation-"));
        } finally {releaseAppend.countDown();}
    }
}
