package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.comment.CommentCommandService;
import com.knode.content.comment.CommentInput;
import com.knode.content.comment.CommentRules;
import com.knode.content.counter.CounterFlushTransaction;
import com.knode.content.counter.CounterStore;
import com.knode.content.relay.HistoricalRegistrar;
import com.knode.content.relay.RelayStream;
import com.knode.content.relay.RelayTransaction;
import com.knode.content.relay.RelayWorker;
import io.jsonwebtoken.Jwts;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class RelayHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired CommentCommandService comments;
    @Autowired CounterFlushTransaction counters;
    @Autowired RelayWorker worker;
    @Autowired RelayTransaction transaction;
    @Autowired HistoricalRegistrar history;
    @MockBean RelayStream stream;
    final List<String> sent=Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean failTransport=new AtomicBoolean();
    final AtomicReference<String> failEvent=new AtomicReference<>();
    final AtomicInteger sequence=new AtomicInteger();
    final AtomicReference<Runnable> sendHook=new AtomicReference<>();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_CONTENT_OPS_PUBLIC_KEYS",() -> "test-key="+AuthenticationHttpTest.PUBLIC_KEY+";rotation-key="+AuthenticationHttpTest.PUBLIC_KEY);
    }
    @BeforeEach void setup() {
        sent.clear();failTransport.set(false);failEvent.set(null);sequence.set(0);sendHook.set(null);reset(stream);
        when(stream.enabled()).thenReturn(true);
        when(stream.publish(anyString())).thenAnswer(call -> {
            Runnable hook=sendHook.getAndSet(null);if(hook!=null)hook.run();
            String raw=call.getArgument(0);
            if(failTransport.get() || failEvent.get()!=null && raw.contains(failEvent.get()))
                throw new IllegalStateException("synthetic transport error");
            sent.add(raw);return sequence.incrementAndGet()+"-0";
        });
        for(String table:List.of("content_article_snapshot_item","content_article_snapshot","content_article_snapshot_gate",
                "content_article_change_fact","content_article_change_batch","content_article_change_head","content_event_backfill","content_event_attempt_audit","content_event_replay_audit","content_event_delivery","content_article_counter_event",
                "content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job",
                "content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'Tag',0)");
    }
    long article(String status) {
        return articles.create("42",new ArticleCommand("title",null,"1","public body",List.of(3L),2L,"summary",status),null);
    }
    long comment(long article,String type) {
        Long id="0".equals(type)?article:1L;
        return comments.create("77",CommentRules.validate(new CommentInput(type,id,-1L,-1L,-1L,"comment original")),null);
    }
    String factId(String table) { return db.queryForObject("SELECT event_id FROM "+table,String.class); }
    String payload(String table,String id) { return db.queryForObject("SELECT payload FROM "+table+" WHERE event_id=?",String.class,id); }
    String state(String id) { return db.queryForObject("SELECT state FROM content_event_delivery WHERE event_id=?",String.class,id); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    void due(String id) { db.update("UPDATE content_event_delivery SET due_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP()) WHERE event_id=?",id); }
    String token(String subject,String audience,String kid,List<String> permissions) {
        Instant now=Instant.now();
        return Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject(subject).audience().add(audience).and()
                .claim("token_use","service").claim("permissions",permissions).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(2))).expiration(Date.from(now.plusSeconds(120)))
                .signWith(AuthenticationHttpTest.KEYS.getPrivate()).compact();
    }
    HttpResponse<String> request(String method,String path,String bearer,String body,String header,String value) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8));
        if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        if(header!=null)b.header(header,value);
        if(body!=null)b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    String replayBody(String id) { return "{\"replayRequestId\":\""+id+"\"}"; }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout"); }
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
    }

    @Test void factsRegisterAtomicallyAndFriendEventsStayLocal() throws Exception {
        long id=article("0");comment(id,"0");comment(id,"1");
        counters.persist(new CounterStore.Snapshot(id,4,"unused"));
        assertEquals(4,count("content_event_delivery"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE state='LOCAL_ONLY'",Integer.class));
        List<String> expected=new ArrayList<>();
        expected.add(payload("content_article_event",factId("content_article_event")));
        String commentId=db.queryForObject("SELECT event_id FROM content_comment_event WHERE event_type='CommentCreated'",String.class);
        expected.add(payload("content_comment_event",commentId));
        expected.add(payload("content_article_counter_event",factId("content_article_counter_event")));
        verify(stream,never()).publish(anyString());
        assertEquals(3,worker.runBatch(10,20));
        assertEquals(3,sent.size());assertTrue(sent.containsAll(expected));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE state='SENT'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE state='LOCAL_ONLY'",Integer.class));
        for(String raw:sent)assertFalse(raw.contains("LinkComment"));
    }

    @Test void registrationFailureRollsBackBusinessAndFactForAllFamilies() {
        db.execute("ALTER TABLE content_event_delivery ADD CONSTRAINT deny_article CHECK (family <> 'ARTICLE')");
        assertThrows(RuntimeException.class,() -> article("0"));
        assertEquals(0,count("content_article"));assertEquals(0,count("content_article_event"));assertEquals(0,count("content_event_delivery"));
        db.execute("ALTER TABLE content_event_delivery DROP CONSTRAINT deny_article");
        long id=article("0");
        db.execute("ALTER TABLE content_event_delivery ADD CONSTRAINT deny_comment CHECK (family <> 'COMMENT')");
        assertThrows(RuntimeException.class,() -> comment(id,"0"));
        assertEquals(0,count("content_comment"));assertEquals(0,count("content_comment_event"));
        db.execute("ALTER TABLE content_event_delivery DROP CONSTRAINT deny_comment");
        db.execute("ALTER TABLE content_event_delivery ADD CONSTRAINT deny_counter CHECK (family <> 'COUNTER')");
        assertThrows(RuntimeException.class,() -> counters.persist(new CounterStore.Snapshot(id,3,"unused")));
        assertEquals(0,db.queryForObject("SELECT view_count FROM content_article WHERE id=?",Long.class,id));assertEquals(0,count("content_article_counter_event"));
    }

    @Test void historicalV1RegistrationIsIdempotentAndPublishesExactBytes() throws Exception {
        article("0");String event=factId("content_article_event");
        ObjectNode v1=(ObjectNode)JSON.readTree(payload("content_article_event",event));v1.put("schemaVersion",1);((ObjectNode)v1.path("payload")).remove("isTop");
        String original=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(v1);
        db.update("UPDATE content_article_event SET payload=? WHERE event_id=?",original,event);
        db.update("DELETE FROM content_event_delivery WHERE event_id=?",event);
        history.runBatch(20);assertEquals("PENDING",state(event));assertEquals(1,count("content_event_delivery"));
        assertEquals(1,worker.runBatch(10,20));assertEquals(List.of(original),sent);assertEquals("SENT",state(event));
        history.runBatch(20);assertEquals("SENT",state(event));assertEquals(1,count("content_event_delivery"));
        assertEquals(original,payload("content_article_event",event));
    }

    @Test void invalidHistoricalNestedObjectIsDeadAndNeverPublished() throws Exception {
        article("0");String event=factId("content_article_event");
        ObjectNode bad=(ObjectNode)JSON.readTree(payload("content_article_event",event));
        ((ObjectNode)bad.path("payload")).putObject("summary").put("secret","private");
        db.update("UPDATE content_article_event SET payload=? WHERE event_id=?",bad.toString(),event);
        assertEquals(0,worker.runBatch(10,20));assertEquals("DEAD",state(event));
        assertEquals("INVALID_EVENT",db.queryForObject("SELECT last_error FROM content_event_delivery WHERE event_id=?",String.class,event));
        verify(stream,never()).publish(anyString());
    }

    @Test void oneConflictingHistoryFamilyDoesNotBlockOthersOrLiveQueue() throws Exception {
        long first=article("0");String conflict=factId("content_article_event");
        db.update("UPDATE content_event_delivery SET family='COMMENT',state='SENT',due_at=NULL WHERE event_id=?",conflict);
        article("0");comment(first,"0");
        assertEquals(2,worker.runBatch(10,20));
        assertEquals("SCAN_FAILED",db.queryForObject("SELECT last_error FROM content_event_backfill WHERE family='ARTICLE'",String.class));
        assertEquals(1,db.queryForObject("SELECT failure_count FROM content_event_backfill WHERE family='ARTICLE'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT completed_passes FROM content_event_backfill WHERE family='ARTICLE'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT completed_passes FROM content_event_backfill WHERE family='COMMENT'",Integer.class));
        assertEquals(2,sent.size());assertEquals("COMMENT",db.queryForObject("SELECT family FROM content_event_delivery WHERE event_id=?",String.class,conflict));
        String read=token("knode-ops","knode-content","test-key",List.of("ops:read"));
        JsonNode backfill=JSON.readTree(request("GET","/internal/v1/ops/outbox/summary",read,null,null,null).body()).path("data").path("backfill");
        boolean visible=false;for(JsonNode item:backfill) if("ARTICLE".equals(item.path("family").asText()))
            visible="SCAN_FAILED".equals(item.path("lastError").asText()) && "1".equals(item.path("failureCount").asText());
        assertTrue(visible);
    }

    @Test void expiredLeaseIsReclaimedAndLateWorkerCannotOverwrite() {
        article("0");RelayTransaction.Claim first=transaction.claim();assertNotNull(first);
        due(first.eventId());assertFalse(transaction.failed(first,"TRANSPORT",false));assertEquals("LEASED",state(first.eventId()));
        RelayTransaction.Claim second=transaction.claim();assertNotNull(second);
        assertNotEquals(first.token(),second.token());assertFalse(transaction.sent(first,"1-0"));
        assertFalse(transaction.failed(first,"TRANSPORT",false));assertEquals("LEASED",state(first.eventId()));
        assertTrue(transaction.sent(second,"2-0"));
        assertEquals("SENT",state(first.eventId()));assertEquals("2-0",db.queryForObject("SELECT stream_id FROM content_event_delivery WHERE event_id=?",String.class,first.eventId()));
        assertEquals("EXPIRED_UNKNOWN",db.queryForObject("SELECT outcome FROM content_event_attempt_audit WHERE lease_token=?",String.class,first.token()));
        assertEquals("SENT",db.queryForObject("SELECT outcome FROM content_event_attempt_audit WHERE lease_token=?",String.class,second.token()));
    }

    @Test void deliveryMarksRecheckDatabaseTimeAfterLockWaitCrossesExpiry() throws Exception {
        article("0");
        assertExpiredMarkAfterLockWait(true);
        assertExpiredMarkAfterLockWait(false);
    }

    void assertExpiredMarkAfterLockWait(boolean success) throws Exception {
        RelayTransaction.Claim claim=transaction.claim();assertNotNull(claim);
        db.update("UPDATE content_event_delivery SET due_at=DATEADD('MILLISECOND',1200,CURRENT_TIMESTAMP(6)) WHERE event_id=?",claim.eventId());
        Timestamp expiry=db.queryForObject("SELECT due_at FROM content_event_delivery WHERE event_id=?",Timestamp.class,claim.eventId());
        var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try(Connection connection=db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement lock=connection.prepareStatement("SELECT event_id FROM content_event_delivery WHERE event_id=? FOR UPDATE")) {
                lock.setString(1,claim.eventId());try(var rows=lock.executeQuery()){assertTrue(rows.next());}
            }
            Future<Boolean> result=pool.submit(() -> {
                started.countDown();
                return success?transaction.sent(claim,"1-0"):transaction.failed(claim,"TRANSPORT",false);
            });
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertTrue(expiry.after(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class)));
            assertThrows(TimeoutException.class,() -> result.get(100,TimeUnit.MILLISECONDS));
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(!db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).after(expiry)) {
                assertTrue(System.nanoTime()<limit,"database clock did not reach expiry");
                Thread.sleep(20);
            }
            connection.commit();
            assertFalse(result.get(3,TimeUnit.SECONDS));
            assertEquals("LEASED",state(claim.eventId()));
            assertEquals("LEASED",db.queryForObject("SELECT outcome FROM content_event_attempt_audit WHERE lease_token=?",String.class,claim.token()));
        } finally {pool.shutdownNow();}
    }

    @Test void claimStartsLeaseUsingDatabaseTimeAfterWaitingForRowLock() throws Exception {
        article("0");String id=factId("content_article_event");due(id);
        var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try(Connection connection=db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement lock=connection.prepareStatement("SELECT event_id FROM content_event_delivery WHERE event_id=? FOR UPDATE")) {
                lock.setString(1,id);try(var rows=lock.executeQuery()){assertTrue(rows.next());}
            }
            Future<RelayTransaction.Claim> result=pool.submit(() -> {started.countDown();return transaction.claim();});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,() -> result.get(200,TimeUnit.MILLISECONDS));
            Timestamp afterWait=db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
            connection.commit();
            RelayTransaction.Claim claim=result.get(3,TimeUnit.SECONDS);assertNotNull(claim);
            Timestamp claimed=db.queryForObject("SELECT claimed_at FROM content_event_attempt_audit WHERE lease_token=?",Timestamp.class,claim.token());
            Timestamp expires=db.queryForObject("SELECT due_at FROM content_event_delivery WHERE event_id=?",Timestamp.class,id);
            assertFalse(claimed.before(afterWait));
            assertEquals(30,Duration.between(claimed.toInstant(),expires.toInstant()).toSeconds());
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentClaimsAreDistinctAndNetworkDoesNotHoldSqlLocks() throws Exception {
        article("0");article("0");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        sendHook.set(() -> {held.countDown();await(release);});
        try {
            Future<Integer> first=pool.submit(() -> worker.runBatch(1,20));assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<Integer> second=pool.submit(() -> worker.runBatch(1,20));assertEquals(1,second.get(5,TimeUnit.SECONDS));
            release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS));assertEquals(2,sent.size());
            assertNotEquals(JSON.readTree(sent.get(0)).path("eventId").asText(),JSON.readTree(sent.get(1)).path("eventId").asText());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void streamSuccessThenSqlFailureRetriesOriginalPayload() {
        article("0");String id=factId("content_article_event");String raw=payload("content_article_event",id);
        db.execute("ALTER TABLE content_event_delivery ADD CONSTRAINT deny_sent CHECK (state <> 'SENT')");
        assertEquals(0,worker.runBatch(1,20));assertEquals(1,sent.size());assertEquals("PENDING",state(id));
        assertEquals("DATABASE",db.queryForObject("SELECT last_error FROM content_event_delivery WHERE event_id=?",String.class,id));
        db.execute("ALTER TABLE content_event_delivery DROP CONSTRAINT deny_sent");due(id);
        assertEquals(1,worker.runBatch(1,20));assertEquals(2,sent.size());assertEquals(raw,sent.get(0));assertEquals(raw,sent.get(1));assertEquals("SENT",state(id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_attempt_audit WHERE event_id=? AND outcome='RETRY' AND error_category='DATABASE'",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_attempt_audit WHERE event_id=? AND outcome='SENT'",Integer.class,id));
    }

    @Test void boundedFailuresBecomeDeadAndCanBeReplayed() throws Exception {
        failTransport.set(true);long id=article("0");String event=factId("content_article_event");
        verify(stream,never()).publish(anyString());
        for(int attempt=1;attempt<=5;attempt++) {assertEquals(0,worker.runBatch(1,20));if(attempt<5)due(event);}
        assertEquals("DEAD",state(event));assertEquals(5,db.queryForObject("SELECT attempts FROM content_event_delivery WHERE event_id=?",Integer.class,event));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM content_event_attempt_audit WHERE event_id=?",Integer.class,event));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_attempt_audit WHERE event_id=? AND outcome='DEAD'",Integer.class,event));
        String op=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        var reply=request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",op,replayBody("retry-1"),null,null);
        assertEquals(200,reply.statusCode());assertEquals("PENDING",state(event));assertEquals(1,count("content_event_replay_audit"));
        failTransport.set(false);assertEquals(1,worker.runBatch(1,20));assertEquals("SENT",state(event));assertEquals(1,sent.size());
    }

    @Test void internalPathAndMethodVariantsRejectUserAndMachineCredentials() throws Exception {
        article("0");String event=factId("content_article_event");
        String user=AuthenticationHttpTest.token("blog",600);
        String read=token("knode-ops","knode-content","test-key",List.of("ops:read"));
        String write=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        for(String path:List.of("/internal/v1/ops/outbox/summary;extra=1",
                "/internal/v1/ops/outbox/summary/",
                "/internal//v1/ops/outbox/summary",
                "/internal;extra=1/v1/ops/outbox/summary",
                "/%69nternal/v1/ops/outbox/summary",
                "/api/../internal/v1/ops/outbox/summary",
                "/internal/v1/ops/outbox/./summary",
                "/internal/v1/ops/outbox/%73ummary",
                "/internal/v1/ops/outbox/summary%3Bextra=1")) {
            for(var response:List.of(request("GET",path,null,null,"token",user),request("GET",path,read,null,null,null)))
                assertTrue(response.statusCode()>=400 && response.statusCode()<500,path+" returned "+response.statusCode());
        }
        for(var response:List.of(
                request("HEAD","/internal/v1/ops/outbox/summary",null,null,"token",user),
                request("HEAD","/internal/v1/ops/outbox/summary",read,null,null,null),
                request("POST","/internal/v1/ops/outbox/"+event+";extra=1/redeliver",write,replayBody("x"),null,null),
                request("POST","/internal/v1/ops/outbox/"+event+"/redeliver;extra=1",write,replayBody("x"),null,null)))
            assertTrue(response.statusCode()>=400 && response.statusCode()<500,"unexpected status "+response.statusCode());
        assertEquals(0,count("content_event_replay_audit"));assertEquals("PENDING",state(event));
    }

    @Test void exactMachinePermissionsReplayIdempotencyAndConflicts() throws Exception {
        long id=article("0");String event=factId("content_article_event");comment(id,"1");
        String local=db.queryForObject("SELECT event_id FROM content_comment_event WHERE event_type='LinkCommentCreated'",String.class);
        String read=token("knode-ops","knode-content","test-key",List.of("ops:read"));
        String write=token("knode-ops","knode-content","rotation-key",List.of("ops:redeliver"));
        String wrongAudience=token("knode-ops","knode-user","test-key",List.of("ops:read"));
        String unknownKid=token("knode-ops","knode-content","unknown",List.of("ops:read"));
        assertEquals(200,request("GET","/internal/v1/ops/outbox/summary",read,null,null,null).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",wrongAudience,null,null,null).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",unknownKid,null,null,null).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",AuthenticationHttpTest.token("blog",600),null,null,null).statusCode());
        assertEquals(403,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",read,replayBody("x"),null,null).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",read,null,"X-Actor","forged").statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",read,null,"X-Api-Key","other").statusCode());
        HttpRequest duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/ops/outbox/summary"))
                .header("Authorization","Bearer "+read).header("Authorization","Bearer "+read).GET().build();
        assertEquals(401,AuthenticationHttpTest.HTTP.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,request("POST","/api/blog/article",write,"{}",null,null).statusCode());
        assertEquals(401,request("POST","/api/blog/article",null,"{}","token",write).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/"+event+"/redeliver",read,null,null,null).statusCode());
        verify(stream,never()).publish(anyString());
        var first=request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",write,replayBody("same"),null,null);
        assertEquals(200,first.statusCode());assertEquals(200,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",write,replayBody("same"),null,null).statusCode());
        assertEquals(1,count("content_event_replay_audit"));
        String other=token("knode-other","knode-content","test-key",List.of("ops:redeliver"));
        assertEquals(409,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",other,replayBody("same"),null,null).statusCode());
        assertEquals(401,request("GET","/internal/v1/ops/outbox/summary",read,null,"token",read).statusCode());
        assertEquals(409,request("POST","/internal/v1/ops/outbox/"+local+"/redeliver",write,replayBody("same"),null,null).statusCode());
        assertEquals(409,request("POST","/internal/v1/ops/outbox/"+local+"/redeliver",write,replayBody("different"),null,null).statusCode());
        assertEquals(400,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",write,"{\"replayRequestId\":\"z\",\"actor\":\"evil\"}",null,null).statusCode());
        assertEquals(400,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",write,"{\"replayRequestId\":\"z\",\"replayRequestId\":\"other\"}",null,null).statusCode());
        RelayTransaction.Claim claim=transaction.claim();assertNotNull(claim);
        assertEquals(409,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",write,replayBody("busy"),null,null).statusCode());
        assertEquals(1,count("content_event_replay_audit"));
    }

    @Test void opsDatabaseFailureIs503RatherThanFalseZero() throws Exception {
        String read=token("knode-ops","knode-content","test-key",List.of("ops:read"));
        db.execute("DROP TABLE content_event_attempt_audit");db.execute("DROP TABLE content_event_replay_audit");db.execute("DROP TABLE content_event_delivery");
        var reply=request("GET","/internal/v1/ops/outbox/summary",read,null,null,null);
        assertEquals(503,reply.statusCode());assertEquals(500,JSON.readTree(reply.body()).path("code").asInt());
        verify(stream,never()).publish(anyString());
    }

    @Test void traceparentIsCapturedAtFactAndResentUnchanged() throws Exception {
        SpanContext span=SpanContext.create("4bf92f3577b34da6a3ce929d0e0e4736","00f067aa0ba902b7",TraceFlags.getSampled(),TraceState.getDefault());
        try(Scope ignored=Span.wrap(span).makeCurrent()) {article("0");}
        String event=factId("content_article_event"),raw=payload("content_article_event",event);
        JsonNode context=JSON.readTree(raw).path("traceContext");
        assertEquals(1,context.size());assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",context.path("traceparent").asText());
        assertEquals(1,worker.runBatch(1,20));assertEquals(raw,sent.get(0));assertFalse(raw.contains("tracestate"));
    }

    @Test void historicalThreeFamiliesAndLateLowerIdAreRegisteredWithoutReset() throws Exception {
        long id=article("0");comment(id,"0");comment(id,"1");counters.persist(new CounterStore.Snapshot(id,2,"unused"));
        String originalId=factId("content_article_event");String original=payload("content_article_event",originalId);
        db.execute("DELETE FROM content_event_delivery");
        history.runBatch(1);
        String cursor=db.queryForObject("SELECT cursor_event_id FROM content_event_backfill WHERE family='ARTICLE'",String.class);
        assertEquals(originalId,cursor);
        ObjectNode late=(ObjectNode)JSON.readTree(original);String lateId="00000000-0000-0000-0000-000000000000";
        late.put("eventId",lateId);late.put("eventType","ArticleContentChanged");late.put("sourceRevision","2");
        db.update("INSERT INTO content_article_event(event_id,article_id,source_revision,event_type,payload,created_at) VALUES(?,?,2,'ArticleContentChanged',?,CURRENT_TIMESTAMP(6))",
                lateId,id,late.toString());
        history.runBatch(1);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE event_id=?",Integer.class,lateId));
        history.runBatch(1);assertEquals("PENDING",state(lateId));
        for(int i=0;i<5;i++)history.runBatch(1);
        assertEquals(5,count("content_event_delivery"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE state='LOCAL_ONLY'",Integer.class));
        db.update("UPDATE content_event_delivery SET state='SENT',due_at=NULL WHERE event_id=?",originalId);
        for(int i=0;i<4;i++)history.runBatch(1);
        assertEquals("SENT",state(originalId));
    }
    @Test void failedEventBacksOffWhileHealthyEventIsPublished() {
        article("0");article("0");
        String failing=db.queryForObject("SELECT event_id FROM content_event_delivery ORDER BY due_at,event_id LIMIT 1",String.class);
        failEvent.set(failing);assertEquals(1,worker.runBatch(10,20));
        assertEquals("PENDING",state(failing));assertEquals("TRANSPORT",db.queryForObject("SELECT last_error FROM content_event_delivery WHERE event_id=?",String.class,failing));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_event_delivery WHERE state='SENT'",Integer.class));assertEquals(1,sent.size());
    }
    @Test void largestSupportedArticleBodyKeepsOriginalBytes() {
        String body="中".repeat(1_000_000);
        articles.create("42",new ArticleCommand("large",null,"1",body,List.of(3L),2L,"summary","0"),null);
        String id=factId("content_article_event"),raw=payload("content_article_event",id);
        assertTrue(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>3_000_000);
        assertEquals(1,worker.runBatch(1,20));assertEquals(raw,sent.get(0));assertEquals("SENT",state(id));
    }

    @Test void concurrentReplayWithSameKeyIsIdempotent() throws Exception {
        article("0");String event=factId("content_article_event");
        String token=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            List<Future<Integer>> replies=new ArrayList<>();
            for(int i=0;i<2;i++)replies.add(pool.submit(() -> {
                await(gate);return request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",token,replayBody("parallel"),null,null).statusCode();
            }));
            gate.countDown();for(var reply:replies)assertEquals(200,reply.get(10,TimeUnit.SECONDS));
            assertEquals(1,count("content_event_replay_audit"));assertEquals("PENDING",state(event));
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentReplayKeyForDifferentEventsQueuesOnlyOneEvent() throws Exception {
        article("0");
        article("0");
        List<String> events=db.queryForList("SELECT event_id FROM content_event_delivery ORDER BY event_id",String.class);
        db.update("UPDATE content_event_delivery SET state='DEAD',attempts=5,total_attempts=5,due_at=NULL");
        String bearer=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        var pool=Executors.newFixedThreadPool(2);
        var gate=new CountDownLatch(1);
        try {
            List<Future<Integer>> replies=new ArrayList<>();
            for (String event : events) {
                replies.add(pool.submit(() -> {
                    await(gate);
                    return request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",bearer,
                            replayBody("shared"),null,null).statusCode();
                }));
            }
            gate.countDown();
            int first=replies.get(0).get(10,TimeUnit.SECONDS);
            int second=replies.get(1).get(10,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),java.util.stream.Stream.of(first,second).sorted().toList());
            String winner=events.get(first==200?0:1);
            String loser=events.get(first==200?1:0);
            assertEquals("PENDING",state(winner));
            assertEquals("DEAD",state(loser));
            assertEquals(5,db.queryForObject("SELECT attempts FROM content_event_delivery WHERE event_id=?",Integer.class,loser));
            assertEquals(winner,db.queryForObject("SELECT event_id FROM content_event_replay_audit",String.class));
            verify(stream,never()).publish(anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void replayAuditFailureRestoresExpiredLeaseAndAttempt() throws Exception {
        article("0");
        RelayTransaction.Claim claim=transaction.claim();
        assertNotNull(claim);
        String event=claim.eventId();
        String fact=payload("content_article_event",event);
        due(event);
        db.execute("ALTER TABLE content_event_replay_audit ADD CONSTRAINT deny_replay CHECK (replay_request_id <> 'retry')");
        String bearer=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        String path="/internal/v1/ops/outbox/"+event+"/redeliver";
        assertEquals(503,request("POST",path,bearer,replayBody("retry"),null,null).statusCode());
        assertEquals("LEASED",state(event));
        assertEquals("LEASED",db.queryForObject("SELECT outcome FROM content_event_attempt_audit WHERE lease_token=?",String.class,claim.token()));
        assertEquals(0,count("content_event_replay_audit"));

        db.execute("ALTER TABLE content_event_replay_audit DROP CONSTRAINT deny_replay");
        assertEquals(200,request("POST",path,bearer,replayBody("retry"),null,null).statusCode());
        assertEquals("PENDING",state(event));
        assertEquals("EXPIRED_UNKNOWN",db.queryForObject("SELECT outcome FROM content_event_attempt_audit WHERE lease_token=?",String.class,claim.token()));
        assertEquals(fact,payload("content_article_event",event));
        assertEquals(1,count("content_event_replay_audit"));
        verify(stream,never()).publish(anyString());
    }

    @Test void replayKeysKeepExactCaseAcrossDatabaseCollations() throws Exception {
        article("0");String event=factId("content_article_event");
        String token=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        assertEquals(200,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",token,replayBody("case"),null,null).statusCode());
        assertEquals(200,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",token,replayBody("CASE"),null,null).statusCode());
        assertEquals(2,count("content_event_replay_audit"));
    }

    @Test void manualReplayOfSentFactReusesExactStoredPayload() throws Exception {
        article("0");String event=factId("content_article_event"),raw=payload("content_article_event",event);
        assertEquals(1,worker.runBatch(1,20));assertEquals("SENT",state(event));
        String token=token("knode-ops","knode-content","test-key",List.of("ops:redeliver"));
        assertEquals(200,request("POST","/internal/v1/ops/outbox/"+event+"/redeliver",token,replayBody("manual"),null,null).statusCode());
        assertEquals("PENDING",state(event));assertEquals(1,worker.runBatch(1,20));
        assertEquals(2,sent.size());assertEquals(raw,sent.get(0));assertEquals(raw,sent.get(1));
        assertEquals(raw,payload("content_article_event",event));assertEquals(1,count("content_event_replay_audit"));
    }

    @Test void missingLeaseAuditBecomesVisibleDeadWithoutBlockingHealthyFact() {
        article("0");article("0");
        String broken=db.queryForObject("SELECT event_id FROM content_event_delivery ORDER BY due_at,event_id LIMIT 1",String.class);
        db.update("UPDATE content_event_delivery SET state='LEASED',lease_token=NULL,due_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP()) WHERE event_id=?",broken);
        assertEquals(1,worker.runBatch(10,20));
        assertEquals("DEAD",state(broken));
        assertEquals("AUDIT_MISSING",db.queryForObject("SELECT last_error FROM content_event_delivery WHERE event_id=?",String.class,broken));
        assertEquals(1,sent.size());assertEquals(2,count("content_article_event"));
    }
}
