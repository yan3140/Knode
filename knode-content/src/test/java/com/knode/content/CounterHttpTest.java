package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.counter.CounterFlushTransaction;
import com.knode.content.counter.CounterStore;
import com.knode.content.counter.CounterStoreFixture;
import com.knode.content.counter.CounterWorker;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class CounterHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static Process fakeServer;
    static int fakePort;
    static final String SERVER="""
            import sys
            sys.path.insert(0,sys.argv[1])
            import fakeredis
            server=fakeredis.TcpFakeServer(("127.0.0.1",0))
            client=fakeredis.FakeRedis(server=server.fake_server)
            client.eval("redis.acl_check_cmd=function(...) return true end; return 'OK'",0)
            from pathlib import Path
            for name in ("increment.lua","claim.lua","finish.lua"):
                client.script_load((Path(sys.argv[2])/name).read_bytes())
            print(server.server_address[1],flush=True)
            server.serve_forever()
            """;
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired CounterWorker worker;
    @Autowired CounterFlushTransaction flush;
    @Autowired PlatformTransactionManager manager;
    @MockBean CounterStore store;
    CounterStoreFixture fixture;
    final AtomicBoolean failHit=new AtomicBoolean(),failRead=new AtomicBoolean(),failFinish=new AtomicBoolean();
    final AtomicInteger readCalls=new AtomicInteger();
    final AtomicReference<Runnable> hitHook=new AtomicReference<>(),finishHook=new AtomicReference<>();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { AuthenticationHttpTest.properties(p); }

    @BeforeAll static void startFake() throws Exception {
        Path deps=Path.of("node_modules","counter-fake-python").toAbsolutePath();
        if(!Files.isDirectory(deps.resolve("fakeredis"))) deps=Path.of("knode-content","node_modules","counter-fake-python").toAbsolutePath();
        Assumptions.assumeTrue(Files.isDirectory(deps.resolve("fakeredis")),"Install isolated fakeredis[lua] fixture to run counter integration");
        String encoded=java.util.Base64.getEncoder().encodeToString(SERVER.getBytes(StandardCharsets.UTF_8));
        String launcher="import base64;exec(base64.b64decode('"+encoded+"'))";
        Path scripts=Path.of("src","main","resources","counter").toAbsolutePath();
        if(!Files.isDirectory(scripts)) scripts=Path.of("knode-content","src","main","resources","counter").toAbsolutePath();
        fakeServer=new ProcessBuilder("python","-u","-X","utf8","-c",launcher,deps.toString(),scripts.toString()).redirectErrorStream(true).start();
        BufferedReader output=new BufferedReader(new InputStreamReader(fakeServer.getInputStream(),StandardCharsets.UTF_8));
        String first=CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch(Exception e) { return null; } }).get(15,TimeUnit.SECONDS);
        assertNotNull(first,"Fake Redis did not start"); fakePort=Integer.parseInt(first.trim());
    }
    @AfterAll static void stopFake() throws Exception {
        if(fakeServer!=null) { fakeServer.destroyForcibly(); fakeServer.waitFor(5,TimeUnit.SECONDS); }
    }
    @BeforeEach void setup() {
        String prefix="knode:test:v1:content:views:test-"+UUID.randomUUID()+":";
        fixture=CounterStoreFixture.open(fakePort,prefix);
        failHit.set(false);failRead.set(false);failFinish.set(false);readCalls.set(0);hitHook.set(null);finishHook.set(null);
        reset(store);
        when(store.enabled()).thenReturn(true);
        when(store.hit(anyLong(),anyLong(),anyBoolean(),any())).thenAnswer(call -> {
            Runnable hook=hitHook.getAndSet(null); if(hook!=null) hook.run();
            if(failHit.get()) throw new IllegalStateException("synthetic redis failure");
            return fixture.store.hit(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));
        });
        when(store.read(anyCollection())).thenAnswer(call -> {
            readCalls.incrementAndGet(); if(failRead.get()) throw new IllegalStateException("synthetic redis failure");
            return fixture.store.read(call.getArgument(0));
        });
        when(store.claim()).thenAnswer(call -> fixture.store.claim());
        doAnswer(call -> {
            Runnable hook=finishHook.getAndSet(null);if(hook!=null) hook.run();
            if(failFinish.getAndSet(false)) throw new IllegalStateException("synthetic cleanup failure");
            fixture.store.finish(call.getArgument(0));return null;
        }).when(store).finish(any());
        when(store.pending()).thenAnswer(call -> fixture.store.pending());
        for(String table:List.of("content_article_counter_event","content_comment_event","content_comment","content_comment_limit",
                "content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event",
                "content_article_tag","content_article","content_tag","content_category")) db.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'Tag',0)");
    }
    @AfterEach void closeFixture() { if(fixture!=null) fixture.close(); }
    long article(String status) {
        return articles.create("42",new ArticleCommand("title",null,"1","body",List.of(3L),2L,"summary",status),null);
    }
    HttpResponse<String> request(String method,String path,String agent,String header,String value) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8));
        if(agent!=null)b.header("User-Agent",agent);
        if(header!=null)b.header(header,value);
        b.method(method,HttpRequest.BodyPublishers.noBody());
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> put(long id,String agent) throws Exception {
        return request("PUT","/api/blog/article/updateViewCount/"+id,agent,null,null);
    }
    JsonNode data(String path) throws Exception {
        HttpResponse<String> r=request("GET",path,null,null,null);
        assertEquals(200,r.statusCode());JsonNode n=JSON.readTree(r.body());assertEquals(200,n.path("code").asInt());return n.path("data");
    }
    long dbCount(long id) { return db.queryForObject("SELECT view_count FROM content_article WHERE id=?",Long.class,id); }
    long revision(long id) { return db.queryForObject("SELECT counter_revision FROM content_article WHERE id=?",Long.class,id); }
    int events() { return db.queryForObject("SELECT COUNT(*) FROM content_article_counter_event",Integer.class); }
    void expireLease(long id) { fixture.redis.opsForZSet().add(fixture.prefix+"dirty",Long.toString(id),0); }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
    }

    @Test void aliasesDedupRateAndIdentityAreBounded() throws Exception {
        long id=article("0");
        var first=put(id,"same-agent");assertEquals(200,first.statusCode());assertEquals(200,JSON.readTree(first.body()).path("code").asInt());
        assertEquals("操作成功",JSON.readTree(first.body()).path("msg").asText());
        var duplicate=request("PUT","/article/updateViewCount/"+id,"same-agent","X-Forwarded-For","198.51.100.8");
        assertEquals(200,duplicate.statusCode());assertEquals(1L,fixture.store.read(List.of(id)).get(id));
        assertEquals(401,request("PUT","/article/updateViewCount/"+id,"same-agent","X-User-Id","42").statusCode());
        assertEquals(401,request("POST","/article/updateViewCount/"+id,"same-agent",null,null).statusCode());
        assertEquals(514,JSON.readTree(request("PUT","/article/updateViewCount/-1","agent",null,null).body()).path("code").asInt());
        assertEquals(514,JSON.readTree(put(id,"x".repeat(513)).body()).path("code").asInt());
        assertEquals(514,JSON.readTree(request("PUT","/article/updateViewCount/9223372036854775808","agent",null,null).body()).path("code").asInt());
        for(int i=0;i<18;i++)assertEquals(200,put(id,"same-agent").statusCode());
        var limited=put(id,"same-agent");assertEquals(429,limited.statusCode());assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertEquals(1L,fixture.store.read(List.of(id)).get(id));
        failHit.set(true);long another=article("0");assertEquals(503,put(another,"x").statusCode());
    }

    @Test void largeIdsCountersBatchReadsAndIndependentEvent() throws Exception {
        db.execute("ALTER TABLE content_article ALTER COLUMN id RESTART WITH 9007199254740993");
        long id=article("0");assertEquals(9007199254740993L,id);
        db.update("UPDATE content_article SET view_count=?,counter_revision=7 WHERE id=?",9007199254740993L,id);
        assertEquals(200,put(id,"unique").statusCode());
        assertEquals("9007199254740994",data("/api/blog/article/"+id).path("viewCount").asText());
        readCalls.set(0);
        assertEquals("9007199254740994",data("/article/articleList?pageNum=1&pageSize=10").path("rows").get(0).path("viewCount").asText());
        assertEquals(1,readCalls.get());
        assertEquals("9007199254740994",data("/article/hotArticleList").get(0).path("viewCount").asText());
        assertEquals(1,worker.runBatch(10));assertEquals(9007199254740994L,dbCount(id));assertEquals(8,revision(id));
        assertEquals(1,db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_article_event WHERE article_id=?",Integer.class,id));
        JsonNode event=JSON.readTree(db.queryForObject("SELECT payload FROM content_article_counter_event WHERE article_id=?",String.class,id));
        assertEquals("ArticleCounterChanged",event.path("eventType").asText());assertEquals("ARTICLE_COUNTER",event.path("aggregateType").asText());
        assertEquals("8",event.path("sourceRevision").asText());assertEquals("9007199254740994",event.path("payload").path("viewCount").asText());
        assertEquals("PUBLIC",event.path("visibility").asText());
        failRead.set(true);db.update("UPDATE content_article SET view_count=6 WHERE id=?",id);
        assertEquals("6",data("/article/"+id).path("viewCount").asText());
    }

    @Test void hiddenAndWithdrawnArticlesNeverCountOrLeak() throws Exception {
        long draft=article("1");assertEquals(200,put(draft,"draft").statusCode());assertTrue(fixture.store.read(List.of(draft)).isEmpty());
        long id=article("0");assertEquals(200,put(id,"visible").statusCode());
        articles.setStatus("42",id,"1",null);assertEquals(200,put(id,"later").statusCode());
        assertEquals(1,worker.runBatch(10));assertEquals(1,dbCount(id));
        JsonNode event=JSON.readTree(db.queryForObject("SELECT payload FROM content_article_counter_event WHERE article_id=?",String.class,id));
        assertEquals("WITHDRAWN",event.path("visibility").asText());assertEquals(513,JSON.readTree(request("GET","/article/"+id,null,null,null).body()).path("code").asInt());
        assertEquals(200,put(999999L,"missing").statusCode());assertTrue(fixture.store.read(List.of(999999L)).isEmpty());
    }

    @Test void concurrentDifferentVisitorsUseOneSharedAtomicCounter() throws Exception {
        long id=article("0");var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<8;i++) { final int n=i;results.add(pool.submit(() -> {await(gate);return put(id,"agent-"+n).statusCode();})); }
            gate.countDown();for(var result:results)assertEquals(200,result.get(15,TimeUnit.SECONDS));
            assertEquals(8L,fixture.store.read(List.of(id)).get(id));assertEquals(1,worker.runBatch(1));assertEquals(8,dbCount(id));
        } finally {pool.shutdownNow();}
    }

    @Test void incrementBetweenSnapshotAndCleanupIsRequeued() throws Exception {
        long id=article("0");assertEquals(200,put(id,"one").statusCode());
        finishHook.set(() -> {try {assertEquals(200,put(id,"two").statusCode());}catch(Exception e){throw new IllegalStateException(e);}});
        assertEquals(1,worker.runBatch(1));assertEquals(1,dbCount(id));assertEquals(1,fixture.store.pending());
        assertEquals(1,worker.runBatch(1));assertEquals(2,dbCount(id));assertEquals(2,events());assertEquals(0,fixture.store.pending());
        assertFalse(flush.persist(new CounterStore.Snapshot(id,1,"0")));assertEquals(2,dbCount(id));assertEquals(2,events());
    }

    @Test void eventFailureRollsBackAndKeepsDirtyForRetry() throws Exception {
        long id=article("0");assertEquals(200,put(id,"one").statusCode());
        db.execute("ALTER TABLE content_article_counter_event ADD CONSTRAINT deny_counter_event CHECK (counter_revision < 0)");
        assertEquals(0,worker.runBatch(1));assertEquals(0,dbCount(id));assertEquals(0,events());assertEquals(1,fixture.store.pending());
        db.execute("ALTER TABLE content_article_counter_event DROP CONSTRAINT deny_counter_event");
        expireLease(id);assertEquals(1,worker.runBatch(1));assertEquals(1,dbCount(id));assertEquals(1,events());assertEquals(0,fixture.store.pending());
    }

    @Test void cleanupFailureAndRestartRecoverWithoutDuplicateEvent() throws Exception {
        long id=article("0");assertEquals(200,put(id,"one").statusCode());failFinish.set(true);
        assertEquals(0,worker.runBatch(1));assertEquals(1,dbCount(id));assertEquals(1,events());assertEquals(1,fixture.store.pending());
        String prefix=fixture.prefix;fixture.close();fixture=CounterStoreFixture.open(fakePort,prefix);
        expireLease(id);assertEquals(1,worker.runBatch(1));assertEquals(1,dbCount(id));assertEquals(1,events());assertEquals(0,fixture.store.pending());
    }

    @Test void concurrentWorkersDoNotLoseOrDuplicateSnapshot() throws Exception {
        long id=article("0");assertEquals(200,put(id,"one").statusCode());
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        finishHook.set(() -> {held.countDown();await(release);});
        try {
            Future<Integer> one=pool.submit(() -> worker.runBatch(1));assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<Integer> two=pool.submit(() -> worker.runBatch(1));assertEquals(0,two.get(5,TimeUnit.SECONDS));
            release.countDown();assertEquals(1,one.get(5,TimeUnit.SECONDS));assertEquals(1,events());assertEquals(1,dbCount(id));assertEquals(0,fixture.store.pending());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void withdrawalCommitsBeforeBlockedHitAndSuppressesIncrement() throws Exception {
        long id=article("0");var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            Future<?> withdraw=pool.submit(() -> tx.execute(status -> {articles.setStatus("42",id,"1",null);held.countDown();await(release);return null;}));
            assertTrue(held.await(5,TimeUnit.SECONDS));Future<Integer> hit=pool.submit(() -> put(id,"late").statusCode());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean blocked=false;
            while(System.nanoTime()<end) {
                if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class)>0) {blocked=true;break;}
                if(hit.isDone())break;Thread.sleep(10);
            }
            assertTrue(blocked);release.countDown();withdraw.get(5,TimeUnit.SECONDS);assertEquals(200,hit.get(5,TimeUnit.SECONDS));
            assertTrue(fixture.store.read(List.of(id)).isEmpty());assertEquals(0,fixture.store.pending());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void redisAdapterExecutesScriptOnIsolatedFake() {
        var identity=new CounterStore.Identity("a".repeat(64),"b".repeat(64),"c".repeat(64));
        assertEquals(0,fixture.store.hit(1,0,true,identity));
        assertEquals(1L,fixture.store.read(List.of(1L)).get(1L));
    }

    @Test void acceptedHitCompletesBeforeWithdrawalAndIsEventuallyPersisted() throws Exception {
        long id=article("0");var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        hitHook.set(() -> {held.countDown();await(release);});
        try {
            Future<Integer> hit=pool.submit(() -> put(id,"before").statusCode());
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<?> withdraw=pool.submit(() -> articles.setStatus("42",id,"1",null));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);boolean blocked=false;
            while(System.nanoTime()<end) {
                if(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class)>0) {blocked=true;break;}
                if(withdraw.isDone())break;Thread.sleep(10);
            }
            assertTrue(blocked);release.countDown();assertEquals(200,hit.get(5,TimeUnit.SECONDS));withdraw.get(5,TimeUnit.SECONDS);
            assertEquals(1,worker.runBatch(1));assertEquals(1,dbCount(id));assertEquals(1,events());
            assertEquals("WITHDRAWN",JSON.readTree(db.queryForObject("SELECT payload FROM content_article_counter_event",String.class)).path("visibility").asText());
            assertEquals(513,JSON.readTree(request("GET","/article/"+id,null,null,null).body()).path("code").asInt());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void pausedIngressCanBeDrainedInBoundedBatches() throws Exception {
        List<Long> ids=List.of(article("0"),article("0"),article("0"));
        for(long id:ids)assertEquals(200,put(id,"settlement").statusCode());
        assertEquals(3,fixture.store.pending());
        for(int round=0;round<3 && fixture.store.pending()>0;round++) worker.runBatch(2);
        assertEquals(0,fixture.store.pending());assertEquals(0,worker.runBatch(2));
        for(long id:ids)assertEquals(1,dbCount(id));
        assertEquals(3,events());
    }
    @Test void disabledCounterRejectsWritesAndRetainsSqlReadBaseline() throws Exception {
        long id=article("0");db.update("UPDATE content_article SET view_count=9 WHERE id=?",id);
        assertEquals("9",data("/article/"+id).path("viewCount").asText());
        when(store.enabled()).thenReturn(false);
        assertEquals(503,put(id,"disabled").statusCode());
        assertEquals("9",data("/article/"+id).path("viewCount").asText());
        verify(store,never()).hit(anyLong(),anyLong(),anyBoolean(),any());
    }

    @Test void publicationAndCounterVersionsAdvanceIndependently() throws Exception {
        long id=article("0");assertEquals(200,put(id,"first").statusCode());assertEquals(1,worker.runBatch(1));
        articles.setStatus("42",id,"1",null);articles.setStatus("42",id,"0",null);
        assertEquals(1,revision(id));assertEquals(3,db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Integer.class,id));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM content_article_event WHERE article_id=?",Integer.class,id));
        assertEquals(200,put(id,"second").statusCode());assertEquals(1,worker.runBatch(1));
        assertEquals(2,dbCount(id));assertEquals(2,revision(id));assertEquals(3,db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Integer.class,id));
        assertEquals(2,events());
    }
}
