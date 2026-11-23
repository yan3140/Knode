package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.counter.CounterFlushTransaction;
import com.knode.content.counter.CounterStore;
import com.knode.content.metadata.ProjectionWorker;
import com.knode.content.metadata.TagInput;
import com.knode.content.metadata.TagService;
import com.knode.content.snapshot.SnapshotExport;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DelegatingTransactionDefinition;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@Import({AuthenticationHttpTest.Fixtures.class, SnapshotHttpTest.H2ReadView.class})
class SnapshotHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VIS = "/internal/v1/content/articles/visibility:batch";
    private static final String CURRENT = "/internal/v1/content/articles/snapshots:batch";
    private static final String EXPORT = "/internal/v1/content/article-snapshots";
    private static final String CHANGES = "/internal/v1/content/article-changes";
    private static final String VIS_READ = "content:visibility:read";
    private static final String SNAP_READ = "content:snapshot:read";
    private static final String OWNER = "knode-search";
    private static final String OTHER = "knode-knowledge";
    private static final String ACTOR = "9007199254740993";
    private static final Instant ORIGINAL = Instant.parse("2020-02-02T00:00:00Z");
    private static final String DB = "jdbc:h2:mem:content_g6;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
    private static final String EPOCH = UUID.randomUUID().toString();
    private static final String KEY = cursorKey();
    private static final String RING = "test-key=" + AuthenticationHttpTest.PUBLIC_KEY + ";overlap-key=" + AuthenticationHttpTest.PUBLIC_KEY;

    @LocalServerPort int port;
    @SpyBean JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @Autowired CounterFlushTransaction counters;
    @Autowired TagService tags;
    @Autowired ProjectionWorker metadata;
    @Autowired SnapshotExport exports;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_CONTENT_JDBC_URL", () -> DB);
        p.add("KNODE_CONTENT_OPS_PUBLIC_KEYS", () -> RING);
        p.add("KNODE_CONTENT_SNAPSHOT_API_ENABLED", () -> true);
        p.add("KNODE_CONTENT_SNAPSHOT_CLEANUP_ENABLED", () -> false);
        p.add("KNODE_CONTENT_SNAPSHOT_EPOCH", () -> EPOCH);
        p.add("KNODE_CONTENT_SNAPSHOT_CURSOR_KEY", () -> KEY);
    }

    @BeforeEach
    void setup() throws Exception {
        try (Connection connection = db.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:content_g6"));
        }
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'original-tag',0)");
    }

    @Test
    void currentBatchesKeepInputOrderAndOnlyPublicBodiesWithExactInt64() throws Exception {
        long draft = create("draft-private-text", "1");
        long withdrawn = create("withdrawn-private-text", "0");
        articles.setStatus(ACTOR, withdrawn, "1", null);
        long deleted = create("deleted-private-text", "0");
        articles.delete(ACTOR, deleted, null);
        long high = 9_007_199_254_740_993L;
        legacy(high, "legacy public", high, Long.MAX_VALUE, high, null, null);
        long missing = Long.MAX_VALUE;
        List<String> ordered = strings(deleted, high, withdrawn, draft, missing);
        List<String> input = new ArrayList<>(ordered);
        input.add(1, Long.toString(deleted));

        JsonNode state = ok(send("POST", VIS, service(OWNER, VIS_READ),
                JSON.writeValueAsString(Map.of("articleIds",input,"purpose","PUBLIC_KNOWLEDGE"))));
        assertEquals(ordered, values(state.path("items"), "articleId"));
        assertEquals(List.of("DELETED","PUBLIC","WITHDRAWN","DRAFT","MISSING"), values(state.path("items"), "visibility"));
        assertEquals(List.of("DENIED","READABLE","DENIED","DENIED","DENIED"), values(state.path("items"), "decision"));
        assertEquals(List.of("2",Long.toString(high),"2","1","null"), values(state.path("items"), "sourceRevision"));

        HttpResponse<String> response = send("POST", CURRENT, service(OWNER, SNAP_READ),
                JSON.writeValueAsString(Map.of("articleIds",input)));
        var result = ok(response).path("items");
        assertEquals(ordered, values(result, "articleId"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        for (JsonNode item : result) if (!"PUBLIC".equals(item.path("visibility").asText())) assertFalse(item.has("snapshot"));
        assertFalse(response.body().contains("private-text"));
        JsonNode article = result.get(1).path("snapshot");
        assertEquals(Long.toString(high), article.path("sourceRevision").textValue());
        assertEquals(Long.toString(Long.MAX_VALUE), article.path("viewCount").textValue());
        assertEquals(Long.toString(high), article.path("counterRevision").textValue());
        assertEquals(ACTOR, article.path("authorId").textValue());
        assertEquals("Java", article.path("category").path("name").asText());
        assertEquals("original-tag", article.path("tags").get(0).path("name").asText());
        assertEquals(ORIGINAL.toString(), article.path("createTime").asText());
        assertTrue(article.path("publishedAt").isNull());
        assertTrue(article.path("publicationFactId").isNull());
    }

    @Test
    void strictInternalRequestsRejectUsersForgedIdentityAndPathVariants() throws Exception {
        String vis = service(OWNER, VIS_READ);
        String snap = service(OWNER, SNAP_READ);
        String good = "{\"articleIds\":[\"1\"],\"purpose\":\"PUBLIC_SEARCH\"}";
        error(401, "INVALID_SERVICE_TOKEN", send("POST",VIS,null,good));
        error(403, "SERVICE_PERMISSION_DENIED", send("POST",VIS,snap,good));
        error(403, "SERVICE_PERMISSION_DENIED", send("POST",CURRENT,vis,"{\"articleIds\":[\"1\"]}"));
        error(401, "INVALID_SERVICE_TOKEN", send("POST",VIS,
                machine(OWNER,"knode-user","test-key",List.of(VIS_READ)),good));
        error(401, "INVALID_SERVICE_TOKEN", send("POST",VIS,
                machine(OWNER,"knode-content","untrusted",List.of(VIS_READ)),good));
        assertEquals(200, send("POST",VIS,machine(OWNER,"knode-content","overlap-key",List.of(VIS_READ)),good).statusCode());
        for (String header : List.of("X-Viewer-Id","X-User-Id","X-Operator","Cookie")) {
            error(401,"INVALID_SERVICE_TOKEN", send(port,"POST",VIS,vis,good,header,"fixture"));
        }
        String user = AuthenticationHttpTest.token("blog",600);
        error(401,"INVALID_SERVICE_TOKEN", send(port,"POST",VIS,null,good,"token",user));
        error(401,"INVALID_SERVICE_TOKEN", send("POST",VIS,user,good));
        error(401,"INVALID_SERVICE_TOKEN", send(port,"POST",VIS,vis,good,"token",user));
        error(401,"INVALID_SERVICE_TOKEN", send(port,"POST",VIS,vis,good,"Authorization","Bearer " + vis));
        assertEquals(401, send("POST","/api/blog/article",snap,"{}").statusCode());
        for (String path : List.of(VIS+";", VIS+"/", VIS.replace("visibility:","visibility%3A"),
                VIS.replace("/content/","//content/"), VIS.replace("/content/","/content;x=1/"))) {
            assertTrue(send("POST",path,vis,good).statusCode() >= 400, path);
            assertTrue(send(port,"POST",path,null,good,"token",user).statusCode() >= 400, path);
        }
        assertEquals(401, send("HEAD", CHANGES+"?cursor=placeholder",snap,null).statusCode());
        assertEquals(401, send("HEAD", EXPORT+"/"+UUID.randomUUID(),snap,null).statusCode());
        for (String invalid : List.of(
                "{\"articleIds\":[1],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[\"01\"],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[\"0\"],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[\"9223372036854775808\"],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[\"1\"],\"purpose\":\"PRIVATE\"}",
                "{\"articleIds\":[\"1\"],\"purpose\":\"SEARCH_REBUILD\"}",
                "{\"articleIds\":[\"1\"],\"purpose\":\"PUBLIC_SEARCH\",\"viewerId\":\"1\"}",
                "{\"articleIds\":[\"1\"],\"purpose\":\"PUBLIC_SEARCH\",\"purpose\":\"PUBLIC_SEARCH\"}",
                good + " {}",
                JSON.writeValueAsString(Map.of("articleIds",java.util.Collections.nCopies(101,"1"),"purpose","PUBLIC_SEARCH")))) {
            error(400,"INVALID_ARGUMENT",send("POST",VIS,vis,invalid));
        }
        error(400,"INVALID_ARGUMENT",send("POST",CURRENT,snap,good));
        error(400,"INVALID_ARGUMENT",send("POST",VIS+"?viewerId=1",vis,good));
        error(400,"INVALID_ARGUMENT",send("GET",CHANGES+"?cursor=x&limit=1&limit=2",snap,null));
        assertEquals(0, scalar("SELECT COUNT(*) FROM content_article_snapshot"));
    }

    @Test
    void g5BaselineAndFrozenPagesSurviveAnotherInstanceAndRepeatedStartup() throws Exception {
        String publication = UUID.randomUUID().toString();
        legacy(10, "G5 legacy A", 7, 22, 5, publication, ORIGINAL.plusSeconds(60));
        legacy(20, "G5 legacy B", 9, 33, 6, UUID.randomUUID().toString(), ORIGINAL.plusSeconds(120));
        String oldId = UUID.randomUUID().toString();
        var old = JSON.createObjectNode()
                .put("eventId",oldId).put("eventType","ArticlePublished").put("schemaVersion",1).put("producer","knode-content")
                .put("aggregateType","ARTICLE").put("aggregateId","10").put("sourceRevision","7").put("publicationFactId",publication)
                .put("occurredAt",ORIGINAL.plusSeconds(60).toString()).put("visibility","PUBLIC");
        var oldBody = old.putObject("payload").put("articleId","10").put("authorId",ACTOR).put("title","G5 legacy A")
                .put("content","body G5 legacy A").put("isComment","1").put("createTime",ORIGINAL.toString())
                .put("publishedAt",ORIGINAL.plusSeconds(60).toString()).put("viewCount","22").put("counterRevision","5");
        oldBody.putObject("category").put("id","2").put("name","Java");
        oldBody.putArray("tags").addObject().put("id","3").put("name","original-tag");
        String rawOld = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(old);
        db.update("""
                INSERT INTO content_article_event(event_id,article_id,source_revision,event_type,payload,created_at)
                VALUES(?,10,7,'ArticlePublished',?,?)
                """, oldId, rawOld, Timestamp.from(ORIGINAL.plusSeconds(60)));

        JsonNode handle = open("restart","SEARCH_REBUILD");
        assertEquals(handle, open("restart","SEARCH_REBUILD"));
        JsonNode first = page(handle,null,1);
        assertEquals(List.of("10"), values(first.path("items"),"articleId"));
        assertEquals("22", first.path("items").get(0).path("viewCount").asText());
        String pageCursor = first.path("nextCursor").asText();
        assertFalse(first.path("done").asBoolean());
        assertEquals(handle.path("incrementalCursor"), first.path("incrementalCursor"));

        articles.edit(ACTOR,20,article("changed after capture","0","new body"),null);
        JsonNode frozen = page(handle,pageCursor,1);
        assertEquals("G5 legacy B",frozen.path("items").get(0).path("title").asText());
        assertTrue(frozen.path("done").asBoolean());
        assertTrue(frozen.path("nextCursor").isNull());
        for (int restart = 0; restart < 2; restart++) {
            try (var other = startReplacement()) {
                int nextPort = ((ServletWebServerApplicationContext) other).getWebServer().getPort();
                assertEquals(frozen,ok(send(nextPort,"GET",pagePath(handle,pageCursor,1),service(OWNER,SNAP_READ),null,null,null)));
                assertEquals(handle,ok(send(nextPort,"POST",EXPORT,service(OWNER,SNAP_READ),createBody("restart","SEARCH_REBUILD"),null,null)));
            }
        }
        JsonNode updates = feed(inc(handle), 100);
        assertEquals(1, updates.path("commits").size());
        JsonNode only = updates.path("commits").get(0).path("events").get(0);
        assertEquals("20", only.path("aggregateId").asText());
        assertEquals("ArticleContentChanged", only.path("eventType").asText());
        assertEquals("10", only.path("sourceRevision").asText());
        assertEquals("changed after capture", only.path("payload").path("title").asText());
        assertEquals(rawOld,db.queryForObject("SELECT payload FROM content_article_event WHERE event_id=?",String.class,oldId));
        assertEquals(2,scalar("SELECT COUNT(*) FROM content_article_event"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM content_article_change_fact"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writerCommitsAfterHeadReadAndBeforeProjectionReadWithoutChangingTheCut() throws Exception {
        long id = create("before cut","0");
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (armed.getAndSet(false)) {
                entered.countDown();
                await(resume);
            }
            return invocation.callRealMethod();
        }).when(db).query(argThat(sql -> sql != null && sql.contains("SELECT id,OCTET_LENGTH(content) FROM content_article")),
                any(RowMapper.class), eq(0L), anyInt());

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> capture = pool.submit(() -> open("mvcc-cut","SEARCH_REBUILD"));
            assertTrue(entered.await(8,TimeUnit.SECONDS), "export did not reach post-watermark query");
            Future<?> writer = pool.submit(() -> articles.edit(ACTOR,id,article("after cut","0","post-cut body"),null));
            writer.get(6,TimeUnit.SECONDS);
            resume.countDown();
            JsonNode handle = capture.get(8,TimeUnit.SECONDS);
            var item = page(handle,null,1).path("items").get(0);
            assertEquals("before cut", item.path("title").asText());
            assertEquals("1", item.path("sourceRevision").asText());
            var update = feed(inc(handle),100).path("commits").get(0).path("events").get(0);
            assertEquals("after cut", update.path("payload").path("title").asText());
            assertEquals("2", update.path("sourceRevision").asText());
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rolledBackAllocationLeavesNoGapAndLowerArticleIdCanCommitLater() throws Exception {
        long low = create("lower draft","1");
        long high = create("higher draft","1");
        assertTrue(low < high);
        JsonNode handle = open("late-commit","SEARCH_REBUILD");
        assertTrue(page(handle,null,1).path("items").isEmpty());
        articles.setStatus(ACTOR,high,"0",null);

        var staged = new CountDownLatch(1);
        var undo = new CountDownLatch(1);
        var laterStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var failed = pool.submit(() -> {
                var transaction = new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
                transaction.executeWithoutResult(status -> {
                    articles.edit(ACTOR,high,article("rolled back","0","never committed"),null);
                    staged.countDown();
                    await(undo);
                    status.setRollbackOnly();
                });
            });
            assertTrue(staged.await(5,TimeUnit.SECONDS));
            var later = pool.submit(() -> {
                laterStarted.countDown();
                articles.setStatus(ACTOR,low,"0",null);
            });
            assertTrue(laterStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,() -> later.get(150,TimeUnit.MILLISECONDS));
            JsonNode seen = feed(inc(handle),1);
            var first = seen.path("commits").get(0);
            assertEquals("1", first.path("commitSequence").asText());
            assertEquals(Long.toString(high), first.path("events").get(0).path("aggregateId").asText());
            assertTrue(seen.path("done").asBoolean());

            undo.countDown();
            failed.get(5,TimeUnit.SECONDS);
            later.get(5,TimeUnit.SECONDS);
            JsonNode next = feed(seen.path("nextCursor").asText(),1);
            assertEquals("2",next.path("commits").get(0).path("commitSequence").asText());
            assertEquals(Long.toString(low),next.path("commits").get(0).path("events").get(0).path("aggregateId").asText());
            assertEquals(2,scalar("SELECT COUNT(*) FROM content_article_change_batch"));
            assertEquals(2,scalar("SELECT COUNT(*) FROM content_article_event"));
            JsonNode empty = feed(next.path("nextCursor").asText(),1);
            assertTrue(empty.path("done").asBoolean());
            assertTrue(empty.path("commits").isEmpty());
            assertFalse(empty.path("nextCursor").asText().isBlank());
        } finally {
            undo.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void bulkDeleteRemainsOneCommitAndTombstonesKeepFrozenPublicBodiesNonAuthorizing() throws Exception {
        long a = create("first old public","0");
        long b = create("second old public","0");
        JsonNode handle = open("delete-edge","SEARCH_REBUILD");
        JsonNode firstPage = page(handle,null,1);
        articles.setStatus(ACTOR,a,"1",null);
        articles.adminDeleteBatch(ACTOR,List.of(a,b),null);
        assertTrue(counters.persist(new CounterStore.Snapshot(a,10,"fixture")));

        JsonNode frozen = page(handle,firstPage.path("nextCursor").asText(),1);
        assertEquals("second old public",frozen.path("items").get(0).path("title").asText());
        JsonNode withdrawn = feed(inc(handle),1);
        JsonNode hide = withdrawn.path("commits").get(0).path("events").get(0);
        assertEquals("WITHDRAWN",hide.path("visibility").asText());
        assertFalse(hide.path("payload").has("content"));
        JsonNode deletion = feed(withdrawn.path("nextCursor").asText(),1);
        var group = deletion.path("commits").get(0).path("events");
        assertEquals(List.of(Long.toString(a),Long.toString(b)),values(group,"aggregateId"));
        assertEquals(List.of("ArticleDeleted","ArticleDeleted"),values(group,"eventType"));
        assertEquals(List.of("3","2"),values(group,"sourceRevision"));
        assertFalse(group.get(0).path("payload").has("content"));
        assertFalse(group.get(1).path("payload").has("content"));
        assertEquals(group,feed(withdrawn.path("nextCursor").asText(),1).path("commits").get(0).path("events"));
        JsonNode count = feed(deletion.path("nextCursor").asText(),1).path("commits").get(0).path("events").get(0);
        assertEquals("ArticleCounterChanged",count.path("eventType").asText());
        assertEquals("DELETED",count.path("visibility").asText());
        assertFalse(count.path("payload").has("content"));
        var current = ok(send("POST",CURRENT,service(OWNER,SNAP_READ),ids(a,b))).path("items");
        assertEquals(List.of("DENIED","DENIED"),values(current,"decision"));
        assertFalse(current.get(0).has("snapshot"));
        assertFalse(current.get(1).has("snapshot"));
        assertEquals(List.of("3","2"),values(current,"sourceRevision"));
        assertTrue(page(open("after-delete","KNOWLEDGE_REBUILD"),null,100).path("items").isEmpty());
    }

    @Test
    void importedPublicArticleWithUnknownFirstPublicationStillWithdrawsAndNeverEmitsAnotherFirst() throws Exception {
        long id = 51;
        legacy(id,"old public with missing provenance",7,0,0,null,null);
        JsonNode handle = open("unknown-first","SEARCH_REBUILD");
        assertEquals("51",page(handle,null,1).path("items").get(0).path("articleId").asText());
        articles.edit(ACTOR,id,article("edited historically public article","0","edited body"),null);
        articles.setStatus(ACTOR,id,"1",null);
        var withdrawn = ok(send("POST",VIS,service(OWNER,VIS_READ),
                JSON.writeValueAsString(Map.of("articleIds",List.of("51"),"purpose","PUBLIC_SEARCH")))).path("items").get(0);
        assertEquals("WITHDRAWN",withdrawn.path("visibility").asText());
        assertEquals("9",withdrawn.path("sourceRevision").asText());
        articles.setStatus(ACTOR,id,"0",null);
        articles.delete(ACTOR,id,null);

        JsonNode updates = feed(inc(handle),100);
        List<JsonNode> events = new ArrayList<>();
        updates.path("commits").forEach(commit -> commit.path("events").forEach(events::add));
        assertEquals(List.of("PUBLIC","WITHDRAWN","PUBLIC","DELETED"),events.stream().map(x -> x.path("visibility").asText()).toList());
        assertEquals(List.of("8","9","10","11"),events.stream().map(x -> x.path("sourceRevision").asText()).toList());
        assertEquals("ArticleContentChanged",events.get(0).path("eventType").asText());
        assertEquals("edited body",events.get(0).path("payload").path("content").asText());
        for (int index : List.of(0,2)) {
            assertTrue(events.get(index).path("publicationFactId").isNull());
            assertTrue(events.get(index).path("payload").path("publishedAt").isNull());
        }
        assertFalse(events.get(1).path("payload").has("content"));
        assertFalse(events.get(3).path("payload").has("content"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM content_article_event WHERE event_type='ArticlePublished'"));
    }

    @Test
    void metadataCompensationAndCounterHaveSeparateVersionsAcrossCut() throws Exception {
        long id = create("before rename","0");
        String original = db.queryForObject("SELECT payload FROM content_article_event WHERE article_id=?",String.class,id);
        JsonNode handle = open("renamed","KNOWLEDGE_REBUILD");
        tags.update(ACTOR,new TagInput(3L,"new-tag",null));
        assertEquals(1, metadata.runBatch(10));
        assertEquals(0, metadata.runBatch(10));
        assertTrue(counters.persist(new CounterStore.Snapshot(id,9_007_199_254_740_995L,"fixture")));

        var old = page(handle,null,1).path("items").get(0);
        assertEquals("original-tag",old.path("tags").get(0).path("name").asText());
        assertEquals("0",old.path("viewCount").asText());
        JsonNode changes = feed(inc(handle),100);
        var event = changes.path("commits").get(0).path("events").get(0);
        var counter = changes.path("commits").get(1).path("events").get(0);
        assertEquals("ArticleContentChanged",event.path("eventType").asText());
        assertEquals("new-tag",event.path("payload").path("tags").get(0).path("name").asText());
        assertEquals("2",event.path("sourceRevision").asText());
        assertEquals("0",event.path("payload").path("counterRevision").asText());
        assertEquals("ArticleCounterChanged",counter.path("eventType").asText());
        assertEquals("1",counter.path("sourceRevision").asText());
        assertEquals("9007199254740995",counter.path("payload").path("viewCount").textValue());
        var now = ok(send("POST",CURRENT,service(OWNER,SNAP_READ),ids(id))).path("items").get(0).path("snapshot");
        assertEquals("new-tag",now.path("tags").get(0).path("name").asText());
        assertEquals("2",now.path("sourceRevision").asText());
        assertEquals("9007199254740995",now.path("viewCount").textValue());
        assertEquals("1",now.path("counterRevision").asText());
        assertEquals(original,db.queryForObject("SELECT payload FROM content_article_event WHERE article_id=? AND source_revision=1",String.class,id));
        assertEquals(1,scalar("SELECT COUNT(*) FROM content_article_event WHERE event_type='ArticlePublished'"));
    }

    @Test
    void cursorsAndHandlesAreBoundToOwnerPurposeTypeAndExpiry() throws Exception {
        create("A","0");
        create("B","0");
        JsonNode first = open("same","SEARCH_REBUILD");
        JsonNode second = open("other","KNOWLEDGE_REBUILD");
        String pageCursor = page(first,null,1).path("nextCursor").asText();
        String a = service(OWNER,SNAP_READ);
        String b = service(OTHER,SNAP_READ);

        error(409,"IDEMPOTENCY_CONFLICT",send("POST",EXPORT,a,createBody("same","KNOWLEDGE_REBUILD")));
        JsonNode otherOwner = ok(send("POST",EXPORT,b,createBody("same","KNOWLEDGE_REBUILD")));
        assertNotEquals(id(first),id(otherOwner));
        error(403,"SERVICE_PERMISSION_DENIED",send("GET",pagePath(first,null,1),b,null));
        error(403,"SERVICE_PERMISSION_DENIED",send("GET",CHANGES+"?cursor="+inc(first),b,null));
        error(400,"INVALID_ARGUMENT",send("GET",pagePath(second,pageCursor,1),a,null));
        error(400,"INVALID_ARGUMENT",send("GET",pagePath(first,inc(first),1),a,null));
        error(400,"INVALID_ARGUMENT",send("GET",CHANGES+"?cursor="+pageCursor,a,null));
        String altered = (inc(first).startsWith("A") ? "B" : "A") + inc(first).substring(1);
        error(400,"INVALID_ARGUMENT",send("GET",CHANGES+"?cursor="+altered,a,null));

        db.update("UPDATE content_article_snapshot SET feed_expires_at=? WHERE snapshot_id=?",past(),id(second));
        String expiredFeed = inc(open("other","KNOWLEDGE_REBUILD"));
        error(410,"CURSOR_EXPIRED",send("GET",CHANGES+"?cursor="+expiredFeed,a,null));
        db.update("UPDATE content_article_snapshot SET expires_at=? WHERE snapshot_id=?",past(),id(first));
        error(410,"SNAPSHOT_EXPIRED",send("GET",pagePath(first,pageCursor,1),a,null));
        error(410,"SNAPSHOT_EXPIRED",send("POST",EXPORT,a,createBody("same","SEARCH_REBUILD")));
        error(410,"SNAPSHOT_EXPIRED",send("GET",EXPORT+"/"+UUID.randomUUID(),a,null));
    }

    @Test
    void exportAdmissionIsBoundedAndCleanupKeepsActiveViewsAndChangeHistory() throws Exception {
        long article = create("admission","0");
        String token = service(OWNER,SNAP_READ);
        try (Connection connection = db.getDataSource().getConnection();
             var lock = connection.prepareStatement("SELECT id FROM content_article_snapshot_gate WHERE id=1 FOR UPDATE")) {
            connection.setAutoCommit(false);
            try (var rs = lock.executeQuery()) {
                assertTrue(rs.next());
            }
            var reply = send("POST",EXPORT,token,createBody("busy","SEARCH_REBUILD"));
            error(429,"RATE_LIMITED",reply);
            assertEquals("5",reply.headers().firstValue("Retry-After").orElseThrow());
            connection.rollback();
        }
        JsonNode expired = open("will-expire","SEARCH_REBUILD");
        JsonNode keep = open("keep","SEARCH_REBUILD");
        open("third","SEARCH_REBUILD");
        open("fourth","SEARCH_REBUILD");
        error(429,"RATE_LIMITED",send("POST",EXPORT,token,createBody("fifth","SEARCH_REBUILD")));
        db.update("UPDATE content_article_snapshot SET expires_at=? WHERE snapshot_id=?",past(),id(expired));
        exports.cleanExpired();
        exports.cleanExpired();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_article_snapshot_item WHERE snapshot_id=?",Integer.class,id(expired)));
        assertEquals(1,db.queryForObject("SELECT items_pruned FROM content_article_snapshot WHERE snapshot_id=?",Integer.class,id(expired)));
        assertEquals(List.of(Long.toString(article)),values(page(keep,null,1).path("items"),"articleId"));
        assertEquals(id(keep),id(open("keep","SEARCH_REBUILD")));
        assertNotEquals(id(expired),id(open("fifth","SEARCH_REBUILD")));
        error(410,"SNAPSHOT_EXPIRED",send("GET",pagePath(expired,null,1),token,null));
        error(410,"SNAPSHOT_EXPIRED",send("POST",EXPORT,token,createBody("will-expire","SEARCH_REBUILD")));
        var retained = feed(inc(expired),1);
        assertTrue(retained.path("done").asBoolean());
        assertEquals(1,scalar("SELECT COUNT(*) FROM content_article_change_fact"));
    }

    @Test
    void byteLimitSplitsFrozenPagesAndPreservesUsableLargeArticles() throws Exception {
        String large = "中".repeat(1_000_000);
        long a = articles.create(ACTOR,article("large A","0",large),null);
        long b = articles.create(ACTOR,article("large B","0",large),null);
        long c = articles.create(ACTOR,article("large C","0",large),null);
        String token = service(OWNER,SNAP_READ);
        error(413,"RESPONSE_TOO_LARGE",send("POST",CURRENT,token,ids(a,b,c)));
        var singleton = ok(send("POST",CURRENT,token,ids(c))).path("items").get(0).path("snapshot").path("content");
        assertEquals(1_000_000,singleton.textValue().length());

        JsonNode export = open("big-page","SEARCH_REBUILD");
        var raw = send("GET",pagePath(export,null,100),token,null);
        var first = ok(raw);
        assertEquals(List.of(Long.toString(a),Long.toString(b)),values(first.path("items"),"articleId"));
        assertTrue(raw.body().getBytes(StandardCharsets.UTF_8).length < 8_100_000);
        assertFalse(first.path("done").asBoolean());
        JsonNode last = page(export,first.path("nextCursor").asText(),100);
        assertEquals(List.of(Long.toString(c)),values(last.path("items"),"articleId"));
        assertTrue(last.path("done").asBoolean());
    }

    @Test
    void journalFailureRollsBackArticleFactRegistrationAndHead() {
        db.execute("ALTER TABLE content_article_change_fact ADD CONSTRAINT deny_article CHECK(family <> 'ARTICLE')");
        assertThrows(RuntimeException.class, () -> create("must roll back","0"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM content_article"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM content_article_event"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM content_event_delivery"));
        assertEquals(0,scalar("SELECT last_sequence FROM content_article_change_head WHERE id=1"));
        assertEquals(0,scalar("SELECT COUNT(*) FROM content_article_change_batch"));
    }

    @Test
    void dependencyOutageOrJournalGapCannotLookLikeAnEmptySuccessfulRead() throws Exception {
        JsonNode handle = open("dependency","SEARCH_REBUILD");
        long article = create("new journal item","0");
        db.update("DELETE FROM content_article_change_fact");
        var gap = send("GET",CHANGES+"?cursor="+inc(handle),service(OWNER,SNAP_READ),null);
        error(503,"DEPENDENCY_UNAVAILABLE",gap);
        assertFalse(JSON.readTree(gap.body()).has("nextCursor"));

        db.execute("ALTER TABLE content_article RENAME TO fixture_unavailable_article");
        error(503,"DEPENDENCY_UNAVAILABLE",send("POST",VIS,service(OWNER,VIS_READ),
                "{\"articleIds\":[\"" + article + "\"],\"purpose\":\"PUBLIC_SEARCH\"}"));
        error(503,"DEPENDENCY_UNAVAILABLE",send("POST",CURRENT,service(OWNER,SNAP_READ),ids(article)));
        error(503,"DEPENDENCY_UNAVAILABLE",send("POST",EXPORT,service(OWNER,SNAP_READ),createBody("new-failed","SEARCH_REBUILD")));
        assertEquals(1,scalar("SELECT COUNT(*) FROM content_article_snapshot"));
    }

    private long create(String title, String status) {
        return articles.create(ACTOR,article(title,status,"body " + title),null);
    }

    private ArticleCommand article(String title, String status, String body) {
        return new ArticleCommand(title,null,"1",body,List.of(3L),2L,"summary",status);
    }

    private void legacy(long id, String title, long revision, long count, long counterRevision, String fact, Instant published) {
        db.update("""
                INSERT INTO content_article(id,title,content,category_id,is_top,is_comment,status,author_id,created_at,updated_at,
                    updated_by,view_count,counter_revision,source_revision,publication_fact_id,published_at,deleted)
                VALUES(?,?,?,2,'0','1','0',?,?,?,?,?,?,?,?,?,0)
                """, id,title,"body "+title,ACTOR,Timestamp.from(ORIGINAL),Timestamp.from(ORIGINAL),
                ACTOR,count,counterRevision,revision,fact,published==null?null:Timestamp.from(published));
        db.update("INSERT INTO content_article_tag(article_id,tag_id) VALUES(?,3)",id);
    }

    private int scalar(String sql) {
        return db.queryForObject(sql,Integer.class);
    }

    private Timestamp past() {
        return Timestamp.from(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant().minusSeconds(10));
    }

    private static String cursorKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static List<String> strings(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(Long::toString).toList();
    }

    private String ids(long... ids) throws Exception {
        return JSON.writeValueAsString(Map.of("articleIds",strings(ids)));
    }

    private static List<String> values(JsonNode items, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : items) result.add(item.path(field).asText("null"));
        return result;
    }

    private static String service(String subject, String... perms) {
        return machine(subject,"knode-content","test-key",List.of(perms));
    }

    private static String machine(String subject, String aud, String kid, List<String> perms) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject(subject).audience().add(aud).and()
                .claim("token_use","service").claim("permissions",perms).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(2))).expiration(Date.from(now.plusSeconds(120)))
                .signWith(AuthenticationHttpTest.KEYS.getPrivate()).compact();
    }

    private JsonNode open(String request, String purpose) throws Exception {
        return ok(send("POST",EXPORT,service(OWNER,SNAP_READ),createBody(request,purpose)));
    }

    private static String createBody(String request, String purpose) throws Exception {
        return JSON.writeValueAsString(Map.of("clientRequestId",request,"purpose",purpose));
    }

    private JsonNode page(JsonNode export, String cursor, int limit) throws Exception {
        return ok(send("GET",pagePath(export,cursor,limit),service(OWNER,SNAP_READ),null));
    }

    private static String pagePath(JsonNode export, String cursor, int limit) {
        return EXPORT+"/"+id(export)+"?limit="+limit+(cursor==null?"":"&cursor="+cursor);
    }

    private JsonNode feed(String cursor, int limit) throws Exception {
        return ok(send("GET",CHANGES+"?cursor="+cursor+"&limit="+limit,service(OWNER,SNAP_READ),null));
    }

    private static String inc(JsonNode handle) {
        return handle.path("incrementalCursor").asText();
    }

    private static String id(JsonNode handle) {
        return handle.path("snapshotId").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),() -> response.body().substring(0,Math.min(1000,response.body().length())));
        return JSON.readTree(response.body());
    }

    private static void error(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status,response.statusCode(),() -> response.body().substring(0,Math.min(1000,response.body().length())));
        var body = JSON.readTree(response.body());
        assertEquals(code,body.path("error").path("code").asText());
        assertTrue(body.path("requestId").isTextual());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        return send(port,method,path,token,body,null,null);
    }

    private static HttpResponse<String> send(int port, String method, String path, String token,
                                              String body, String header, String value) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(27));
        if (token != null) builder.header("Authorization","Bearer "+token);
        if (header != null) builder.header(header,value);
        if (body != null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return AuthenticationHttpTest.HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private static org.springframework.context.ConfigurableApplicationContext startReplacement() {
        return new SpringApplicationBuilder(ContentApplication.class, H2ReadView.class).run(
                "--spring.main.banner-mode=off","--server.port=0","--management.server.port=0","--KNODE_CONTENT_PORT=0","--KNODE_MANAGEMENT_PORT=0",
                "--KNODE_ENVIRONMENT=test","--KNODE_CONTENT_JDBC_URL="+DB,"--KNODE_CONTENT_DB_USERNAME=sa","--KNODE_CONTENT_DB_PASSWORD=test",
                "--KNODE_AUTH_REDIS_HOST=127.0.0.1","--KNODE_AUTH_REDIS_PORT=1","--KNODE_AUTH_REDIS_USERNAME=test","--KNODE_AUTH_REDIS_PASSWORD=test",
                "--KNODE_AUTH_PUBLIC_KEY_ID=test-key","--KNODE_AUTH_PUBLIC_KEY_FILE="+AuthenticationHttpTest.PUBLIC_KEY,
                "--KNODE_CONTENT_OPS_PUBLIC_KEYS="+RING,"--KNODE_CONTENT_SNAPSHOT_API_ENABLED=true","--KNODE_CONTENT_SNAPSHOT_CLEANUP_ENABLED=false",
                "--KNODE_CONTENT_SNAPSHOT_EPOCH="+EPOCH,"--KNODE_CONTENT_SNAPSHOT_CURSOR_KEY="+KEY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class H2ReadView {
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource) {
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
                    // H2 RR snapshots maps as they are first touched. H2 SERIALIZABLE emulates the
                    // transaction-wide read view observed on the supported MySQL runtime.
                    if (definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
                        super.doBegin(transaction, new DelegatingTransactionDefinition(definition) {
                            @Override public int getIsolationLevel() {
                                return TransactionDefinition.ISOLATION_SERIALIZABLE;
                            }
                        });
                    } else {
                        super.doBegin(transaction, definition);
                    }
                }
            };
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("fixture barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted",e);
        }
    }
}
