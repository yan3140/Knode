package com.knode.user;

import com.knode.user.auth.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledRecoveryTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SessionStore.Reader reader;
    @MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        UserHttpIntegrationTest.properties(registry);
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:schedule_recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "100");
        registry.add("KNODE_AUTH_REVOCATION_POLL_MS",() -> "100");
    }
    @BeforeAll void schema() {new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);}
    @Test void scheduledWorkerRetriesFailureAndRecordsDurableFact() throws Exception {
        String sid=UUID.randomUUID().toString(), event=UUID.randomUUID().toString();
        doThrow(new SessionStore.Unavailable("offline")).doNothing().when(writer).revoke(sid);
        jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES(?,42,'blog',?,'PENDING')",sid,event);
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos();
        String state="PENDING";
        while (System.nanoTime()<deadline && !"REVOKED".equals(state)) {
            Thread.sleep(100);
            state=jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sid);
        }
        assertEquals("REVOKED",state);
        assertTrue(jdbc.queryForObject("SELECT attempts FROM auth_revocation_intent WHERE sid=?",Integer.class,sid)>=1);
        assertEquals(event,jdbc.queryForObject("SELECT event_id FROM auth_revocation_outbox WHERE sid=? AND state='PENDING'",String.class,sid));
        verify(writer,atLeast(2)).revoke(sid);
    }
}
