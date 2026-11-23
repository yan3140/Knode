package com.knode.content;

import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.comment.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class CommentConcurrencyTest {
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired CommentCommandService comments;
    @Autowired CommentQueryTransaction query;
    @Autowired CommentRateLimit rate;
    @Autowired PlatformTransactionManager manager;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) { AuthenticationHttpTest.properties(p); }
    @BeforeEach void setup() {
        for(String table:List.of("content_article_snapshot_item","content_article_snapshot","content_article_snapshot_gate",
                "content_article_change_fact","content_article_change_batch","content_article_change_head","content_event_backfill","content_event_attempt_audit","content_event_replay_audit","content_event_delivery","content_article_counter_event","content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'Tag',0)");
    }
    long article() {
        return articles.create("42",new ArticleCommand("title",null,"1","body",List.of(3L),2L,"summary","0"),null);
    }
    CommentRules.Valid input(long id) { return CommentRules.validate(new CommentInput("0",id,-1L,-1L,-1L,"hello")); }
    TransactionTemplate readCommitted() {
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;
    }
    @Test void commentCommitBeforeWithdrawalIsStoredButLaterReadsHideIt() throws Exception {
        long a=article();var pool=Executors.newFixedThreadPool(2);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try {
            Future<Long> create=pool.submit(() -> readCommitted().execute(status -> {
                long id=comments.create("77",input(a),null);held.countDown();await(release);return id;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<?> withdraw=pool.submit(() -> { started.countDown();articles.setStatus("42",a,"1",null); });
            assertTrue(started.await(5,TimeUnit.SECONDS));assertBlocked(withdraw);release.countDown();
            long id=create.get(10,TimeUnit.SECONDS);withdraw.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_comment_event WHERE comment_id=?",Integer.class,id));
            assertEquals(2,db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Integer.class,a));
            assertEquals(513,assertThrows(CommentFailure.class,() -> query.read("0",a,1,10)).code());
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void withdrawalCommitBeforeCommentRejectsWithoutWriting() throws Exception {
        long a=article();var pool=Executors.newFixedThreadPool(2);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try {
            Future<?> withdraw=pool.submit(() -> readCommitted().execute(status -> {
                articles.setStatus("42",a,"1",null);held.countDown();await(release);return null;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<Long> create=pool.submit(() -> { started.countDown();return comments.create("77",input(a),null); });
            assertTrue(started.await(5,TimeUnit.SECONDS));assertBlocked(create);release.countDown();
            withdraw.get(10,TimeUnit.SECONDS);
            ExecutionException failure=assertThrows(ExecutionException.class,() -> create.get(10,TimeUnit.SECONDS));
            assertInstanceOf(CommentFailure.class,failure.getCause());assertEquals(513,((CommentFailure)failure.getCause()).code());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_comment",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_comment_event",Integer.class));
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void concurrentDuplicateSubmissionsShareOneLimitAcrossThreads() throws Exception {
        var pool=Executors.newFixedThreadPool(8);var gate=new CountDownLatch(1);
        try {
            var futures=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> { await(gate);return rate.consume("77","127.0.0.1","same"); }));
            gate.countDown();int accepted=0,blocked=0;
            for(Future<Integer> future:futures) { int retry=future.get(15,TimeUnit.SECONDS);if(retry==0)accepted++;else blocked++; }
            assertEquals(1,accepted);assertEquals(7,blocked);
        } finally { pool.shutdownNow(); }
    }
    void assertBlocked(Future<?> future) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        while(System.nanoTime()<deadline) {
            Integer blocked=db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL",Integer.class);
            if(blocked!=null && blocked>0) { assertFalse(future.isDone());return; }
            if(future.isDone()) fail("Competing transaction did not wait for the fence");
            Thread.sleep(10);
        }
        fail("No database lock wait observed");
    }
    static void await(CountDownLatch latch) {
        try { latch.await(); } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
    }
}
