package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.article.ArticleFailure;
import com.knode.content.metadata.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class MetadataProjectionTest {
    static final String ACTOR="42";
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired TagService tags;
    @Autowired ProjectionWorker worker;
    @Autowired ProjectionTransaction tx;
    @Autowired ApplicationContext context;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper json;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { AuthenticationHttpTest.properties(p); }

    @BeforeEach void setup() {
        for(String table:List.of("content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,remark,del_flag) VALUES(3,'Old','note',0),(4,'Other','note',0)");
    }
    ArticleCommand article(String status,String title,long tag) { return new ArticleCommand(title,null,"1","body-"+title,List.of(tag),2L,"summary",status); }
    long create(String status,String title,long tag) { return articles.create(ACTOR,article(status,title,tag),null); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    List<String> events(long id) { return db.queryForList("SELECT payload FROM content_article_event WHERE article_id=? ORDER BY source_revision",String.class,id); }
    long revision(long id) { return db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Long.class,id); }

    @Test void repeatedRenameUsesCurrentDictionaryAndDraftPublishesLatest() throws Exception {
        long published=create("0","public",3), draft=create("1","draft",3);
        String original=events(published).get(0);
        tags.update(ACTOR,new TagInput(3L,"First",null));
        tags.update(ACTOR,new TagInput(3L,"Second",null));
        assertEquals(2,count("content_metadata_job")); assertEquals(2,count("content_metadata_target"));
        assertTrue(events(draft).isEmpty());
        assertEquals(2,worker.runBatch(10));
        assertEquals(original,events(published).get(0));
        assertEquals("Second",json.readTree(events(published).get(1)).path("payload").path("tags").get(0).path("name").asText());
        assertEquals("Second",latest(published).path("payload").path("tags").get(0).path("name").asText());
        assertEquals(1,events(published).stream().filter(x -> x.contains("ArticlePublished")).count());
        articles.setStatus(ACTOR,draft,"0",null);
        assertEquals("Second",latest(draft).path("payload").path("tags").get(0).path("name").asText());
        tags.update(ACTOR,new TagInput(3L,null,"remark only"));
        assertEquals(2,count("content_metadata_job"));
    }
    @Test void metadataTransactionFailureRollsBackTagJobAndTargets() {
        long article=create("0","one",3);
        String original=events(article).get(0);
        db.execute("ALTER TABLE content_metadata_target ADD CONSTRAINT forbid_target CHECK (state <> 'PENDING')");
        assertThrows(RuntimeException.class,() -> tags.update(ACTOR,new TagInput(3L,"New",null)));
        assertEquals("Old",db.queryForObject("SELECT name FROM content_tag WHERE id=3",String.class));
        assertEquals(0,count("content_metadata_job")); assertEquals(0,count("content_metadata_target"));
        assertEquals(1,revision(article)); assertEquals(original,events(article).get(0));
        assertThrows(RuntimeException.class,() -> tags.delete(ACTOR,List.of(3L)));
        assertEquals(0,db.queryForObject("SELECT del_flag FROM content_tag WHERE id=3",Integer.class));
        assertEquals(0,count("content_metadata_job")); assertEquals(0,count("content_metadata_target"));
        assertEquals(original,events(article).get(0));
    }
    @Test void failuresAreRetriedFromPersistentTargetsAndDoNotBlockOthers() throws Exception {
        long a=create("0","one",3), b=create("0","two",4);
        String original=events(a).get(0);
        tags.update(ACTOR,new TagInput(3L,"A",null)); tags.update(ACTOR,new TagInput(4L,"B",null));
        db.execute("ALTER TABLE content_article_event ADD CONSTRAINT fail_one CHECK (event_type <> 'ArticleContentChanged' OR article_id <> "+a+")");
        Instant firstBefore=databaseNow();
        assertEquals(1,worker.runBatch(10));
        Instant firstAfter=databaseNow();
        assertRetryWindow(a,firstBefore,firstAfter,2);
        assertEquals(0,worker.runBatch(10));
        assertEquals(1,revision(a)); assertEquals(2,revision(b));
        assertEquals(1,db.queryForObject("SELECT attempts FROM content_metadata_target WHERE article_id=?",Integer.class,a));
        assertEquals("PENDING",db.queryForObject("SELECT state FROM content_metadata_target WHERE article_id=?",String.class,a));
        assertEquals("PROCESSING_FAILURE",db.queryForObject("SELECT error_code FROM content_metadata_target WHERE article_id=?",String.class,a));
        assertEquals(original,events(a).get(0));
        makeDue(a);
        Instant secondBefore=databaseNow();
        assertEquals(0,worker.runBatch(10));
        Instant secondAfter=databaseNow();
        assertEquals(2,db.queryForObject("SELECT attempts FROM content_metadata_target WHERE article_id=?",Integer.class,a));
        assertRetryWindow(a,secondBefore,secondAfter,4);
        makeDue(a);
        db.update("UPDATE content_metadata_target SET attempts=11 WHERE article_id=?",a);
        Instant cappedBefore=databaseNow();
        assertEquals(0,worker.runBatch(10));
        Instant cappedAfter=databaseNow();
        assertEquals(12,db.queryForObject("SELECT attempts FROM content_metadata_target WHERE article_id=?",Integer.class,a));
        assertRetryWindow(a,cappedBefore,cappedAfter,3600);
        assertEquals(1,revision(a)); assertEquals(original,events(a).get(0));
        db.execute("ALTER TABLE content_article_event DROP CONSTRAINT fail_one");
        makeDue(a);
        ProjectionWorker restarted=new ProjectionWorker(db,tx);
        assertEquals(1,restarted.runBatch(10)); assertEquals(2,revision(a));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM content_metadata_target WHERE state='DONE'",Integer.class));
        assertEquals("A",json.readTree(events(a).get(1)).path("payload").path("tags").get(0).path("name").asText());
    }
    Instant databaseNow() {
        return Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class)).toInstant();
    }
    void makeDue(long articleId) {
        db.update("UPDATE content_metadata_target SET next_attempt=? WHERE article_id=?",Timestamp.from(databaseNow().minusSeconds(1)),articleId);
    }
    void assertRetryWindow(long id,Instant before,Instant after,long seconds) {
        Instant next=Objects.requireNonNull(db.queryForObject("SELECT next_attempt FROM content_metadata_target WHERE article_id=?",Timestamp.class,id)).toInstant();
        assertFalse(next.isBefore(before.plusSeconds(seconds)),"retry scheduled too early");
        assertFalse(next.isAfter(after.plusSeconds(seconds)),"retry scheduled too late");
    }
    @Test void dueComparisonUsesSameDatabaseTimestampPrecision() {
        // H2 rounds a TIMESTAMP(6) upward, but retains nanos in a bound Timestamp parameter.
        Timestamp sample=Timestamp.valueOf("2026-11-11 12:00:00.123456600");
        assertEquals(Boolean.FALSE,db.queryForObject("SELECT CAST(? AS TIMESTAMP(6)) <= ?",Boolean.class,sample,sample));
        assertEquals(Boolean.TRUE,db.queryForObject("SELECT CAST(? AS TIMESTAMP(6)) <= CAST(? AS TIMESTAMP(6))",Boolean.class,sample,sample));
        long id=create("0","precision",3);
        tags.update(ACTOR,new TagInput(3L,"New",null));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_metadata_target WHERE article_id=? AND next_attempt<=CURRENT_TIMESTAMP(6)",Integer.class,id));
        assertEquals(1,worker.runBatch(10));
        assertEquals("DONE",db.queryForObject("SELECT state FROM content_metadata_target WHERE article_id=?",String.class,id));
    }
    @Test void twoWorkersCannotDuplicateACompletedTarget() throws Exception {
        long id=create("0","one",3);
        tags.update(ACTOR,new TagInput(3L,"New",null));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate=new CountDownLatch(1);
            Future<Integer> a=pool.submit(() -> { await(gate); return worker.runBatch(10); });
            Future<Integer> b=pool.submit(() -> { await(gate); return worker.runBatch(10); });
            gate.countDown(); assertEquals(1,a.get(15,TimeUnit.SECONDS)+b.get(15,TimeUnit.SECONDS));
            assertEquals(2,revision(id)); assertEquals(2,events(id).size());
            assertEquals("DONE",db.queryForObject("SELECT state FROM content_metadata_target",String.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentTagChangeAndArticleMutationsConvergeWithoutResurrection() throws Exception {
        long a=create("0","one",3), b=create("0","two",3), c=create("0","three",3), d=create("1","draft",3);
        String originalA=events(a).get(0);
        db.update("UPDATE content_article SET view_count=123,counter_revision=7 WHERE id=?",a);
        ExecutorService pool=Executors.newFixedThreadPool(7);
        try {
            CountDownLatch gate=new CountDownLatch(1);
            List<Future<?>> futures=List.of(
                pool.submit(() -> { await(gate); tags.update(ACTOR,new TagInput(3L,"Rename-A",null)); }),
                pool.submit(() -> { await(gate); tags.update(ACTOR,new TagInput(3L,"Rename-B",null)); }),
                pool.submit(() -> { await(gate); articles.edit(ACTOR,a,article("0","edited",3),null); }),
                pool.submit(() -> { await(gate); articles.setStatus(ACTOR,b,"1",null); }),
                pool.submit(() -> { await(gate); articles.delete(ACTOR,c,null); }),
                pool.submit(() -> { await(gate); articles.setStatus(ACTOR,d,"0",null); }));
            Future<Long> created=pool.submit(() -> { await(gate); return create("0","concurrent-create",3); });
            gate.countDown(); for(Future<?> f:futures) f.get(20,TimeUnit.SECONDS);
            long e=created.get(20,TimeUnit.SECONDS);
            String finalName=db.queryForObject("SELECT name FROM content_tag WHERE id=3",String.class);
            while(worker.runBatch(100)>0) { }
            assertEquals(finalName,latest(a).path("payload").path("tags").get(0).path("name").asText());
            assertEquals(finalName,latest(d).path("payload").path("tags").get(0).path("name").asText());
            assertEquals(finalName,latest(e).path("payload").path("tags").get(0).path("name").asText());
            assertEquals(1,events(e).stream().filter(v -> v.contains("ArticlePublished")).count());
            assertEquals(2,count("content_metadata_job"));
            assertEquals("WITHDRAWN",latest(b).path("visibility").asText());
            assertEquals("DELETED",latest(c).path("visibility").asText());
            assertFalse(latest(b).path("payload").has("content")); assertFalse(latest(c).path("payload").has("content"));
            assertEquals(originalA,events(a).get(0));
            assertEquals(1,events(a).stream().filter(v -> v.contains("ArticlePublished")).count());
            assertEquals(1,events(d).stream().filter(v -> v.contains("ArticlePublished")).count());
            assertEquals("123",latest(a).path("payload").path("viewCount").asText());
            assertEquals("7",latest(a).path("payload").path("counterRevision").asText());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_metadata_target WHERE state='PENDING'",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void softDeletedTagsStayInHistoryButNotInNewPublicProjection() throws Exception {
        long id=create("0","one",3); String old=events(id).get(0);
        tags.delete(ACTOR,List.of(3L));
        assertThrows(ArticleFailure.class,() -> create("0","invalid",3));
        assertEquals(1,worker.runBatch(10));
        assertEquals(0,latest(id).path("payload").path("tags").size());
        assertEquals(old,events(id).get(0));
        JsonNode first=json.readTree(old), refreshed=latest(id);
        assertEquals(first.path("publicationFactId"),refreshed.path("publicationFactId"));
        for(String field:List.of("authorId","title","content","publishedAt","viewCount","counterRevision"))
            assertEquals(first.path("payload").path(field),refreshed.path("payload").path(field));
        assertEquals("ArticleContentChanged",refreshed.path("eventType").asText());
        assertEquals(1,events(id).stream().filter(v -> v.contains("ArticlePublished")).count());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_article_tag WHERE article_id=? AND tag_id=3",Integer.class,id));
        articles.edit(ACTOR,id,new ArticleCommand("one",null,"1","body",List.of(),2L,"summary","0"),null);
        assertEquals(0,latest(id).path("payload").path("tags").size());
    }
    @Test void uncommittedCreateBlocksRenameAndIsRegisteredAfterCommit() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch held=new CountDownLatch(1), release=new CountDownLatch(1), started=new CountDownLatch(1);
        try {
            Future<Long> creating=pool.submit(() -> readCommitted().execute(status -> {
                long id=create("0","late-create",3);
                held.countDown(); await(release); return id;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<?> renaming=pool.submit(() -> { started.countDown(); tags.update(ACTOR,new TagInput(3L,"New",null)); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertBlockedOnDatabaseLock(renaming);
            release.countDown();
            long id=creating.get(10,TimeUnit.SECONDS); renaming.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_metadata_target WHERE article_id=? AND next_attempt<=CURRENT_TIMESTAMP(6)",Integer.class,id));
            assertEquals(1,worker.runBatch(10),db.queryForList("SELECT state,attempts,next_attempt,error_code FROM content_metadata_target").toString());
            assertEquals("New",latest(id).path("payload").path("tags").get(0).path("name").asText());
            assertEquals(1,events(id).stream().filter(v -> v.contains("ArticlePublished")).count());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void uncommittedRenameBlocksCreateWhoseFirstSnapshotUsesNewName() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch held=new CountDownLatch(1), release=new CountDownLatch(1), started=new CountDownLatch(1);
        try {
            Future<?> renaming=pool.submit(() -> readCommitted().execute(status -> {
                tags.update(ACTOR,new TagInput(3L,"New",null));
                held.countDown(); await(release); return null;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<Long> creating=pool.submit(() -> { started.countDown(); return create("0","later-create",3); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertBlockedOnDatabaseLock(creating);
            release.countDown();
            renaming.get(10,TimeUnit.SECONDS); long id=creating.get(10,TimeUnit.SECONDS);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_metadata_target WHERE article_id=?",Integer.class,id));
            assertEquals(1,events(id).size());
            assertEquals("New",latest(id).path("payload").path("tags").get(0).path("name").asText());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    org.springframework.transaction.support.TransactionTemplate readCommitted() {
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return outer;
    }
    void assertBlockedOnDatabaseLock(Future<?> future) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        while(System.nanoTime()<deadline) {
            Integer blocked=db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class);
            if(blocked!=null && blocked>0) { assertFalse(future.isDone()); return; }
            if(future.isDone()) fail("Competing transaction finished without waiting for the fence");
            Thread.sleep(10);
        }
        fail("Competing transaction did not block on a database lock");
    }
    @Test void incompatibleOuterIsolationFailsBeforeContentWrites() {
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,() -> outer.execute(status -> create("0","bad-isolation",3)));
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void workerIsDisabledByDefaultAndRejectsUnboundedBatches() {
        assertTrue(context.getBeansOfType(ProjectionSchedule.class).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> worker.runBatch(0));
        assertThrows(IllegalArgumentException.class,() -> worker.runBatch(101));
        assertThrows(IllegalArgumentException.class,() -> new ProjectionSchedule(worker,0,5000));
        assertThrows(IllegalArgumentException.class,() -> new ProjectionSchedule(worker,10,999));
    }
    JsonNode latest(long id) throws Exception { List<String> list=events(id); return json.readTree(list.get(list.size()-1)); }
    static void await(CountDownLatch l) { try { l.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
}
