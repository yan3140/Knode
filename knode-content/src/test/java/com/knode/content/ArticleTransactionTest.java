package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.article.ArticleFailure;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class ArticleTransactionTest {
    static final String ACTOR = "9007199254740993";
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService service;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { AuthenticationHttpTest.properties(p); }

    @BeforeEach void setup() {
        for (String table : List.of("content_article_snapshot_item","content_article_snapshot","content_article_snapshot_gate",
                "content_article_change_fact","content_article_change_batch","content_article_change_head","content_event_backfill","content_event_attempt_audit","content_event_replay_audit","content_event_delivery","content_article_counter_event","content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'Redis',0),(4,'Spring',0)");
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1, AuthenticationHttpTest.SID, ACTOR, "blog", "ACTIVE", Instant.now().getEpochSecond()+1800)));
    }
    private ArticleCommand article(String status, String title, String body, List<Long> tags) {
        return new ArticleCommand(title,null,"1",body,tags,2L,"summary",status);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table,Integer.class); }
    private long revision(long id) { return db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Long.class,id); }
    private List<String> events(long id) {
        return db.queryForList("SELECT payload FROM content_article_event WHERE article_id=? ORDER BY source_revision",String.class,id);
    }

    @Test void publicationLifecyclePreservesOriginalFactsAndNeverLeaksDraft() throws Exception {
        long id=service.create(ACTOR,article("1","draft","private draft",List.of(3L)), "req-1");
        assertEquals(0,count("content_article_event"));
        service.setStatus(ACTOR,id,"0","req-2");
        String first=events(id).get(0);
        JsonNode published=json.readTree(first);
        assertEquals("ArticlePublished",published.path("eventType").asText());
        assertEquals(ACTOR,published.path("payload").path("authorId").asText());
        assertEquals("Java",published.path("payload").path("category").path("name").asText());
        assertEquals("Redis",published.path("payload").path("tags").get(0).path("name").asText());
        assertEquals("req-2",published.path("correlationId").asText());
        String fact=published.path("publicationFactId").asText();
        String publishedAt=published.path("payload").path("publishedAt").asText();
        db.update("UPDATE content_article SET view_count=123,counter_revision=4 WHERE id=?",id);
        service.edit(ACTOR,id,article("0","changed","new body",List.of(4L)),null);
        assertEquals("123", json.readTree(events(id).get(1)).path("payload").path("viewCount").asText());
        assertEquals("4", json.readTree(events(id).get(1)).path("payload").path("counterRevision").asText());
        assertEquals("0", published.path("payload").path("viewCount").asText());
        service.setStatus(ACTOR,id,"1",null);
        service.edit(ACTOR,id,article("1","secret","private replacement",List.of(3L)),null);
        service.setStatus(ACTOR,id,"0",null);
        service.delete(ACTOR,id,null);
        List<String> all=events(id);
        assertEquals(5,all.size());
        assertEquals(first,all.get(0));
        assertEquals(1,all.stream().filter(s -> s.contains("ArticlePublished")).count());
        assertEquals(fact,json.readTree(all.get(3)).path("publicationFactId").asText());
        assertEquals(publishedAt,json.readTree(all.get(3)).path("payload").path("publishedAt").asText());
        assertFalse(all.get(2).contains("new body"));
        assertFalse(all.get(4).contains("private replacement"));
        assertEquals("ArticleDeleted",json.readTree(all.get(4)).path("eventType").asText());
        assertEquals(7,revision(id));
        assertThrows(ArticleFailure.class,() -> service.setStatus(ACTOR,id,"0",null));
    }

    @Test void failedEventInsertRollsBackArticleRelationsAndPublicationState() {
        db.execute("ALTER TABLE content_article_event ADD CONSTRAINT forbid_publication CHECK (event_type <> 'ArticlePublished')");
        assertThrows(RuntimeException.class,() -> service.create(ACTOR,article("0","public","body",List.of(3L)),null));
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_tag")); assertEquals(0,count("content_article_event"));
        long id=service.create(ACTOR,article("1","draft","private",List.of(3L)),null);
        assertThrows(RuntimeException.class,() -> service.edit(ACTOR,id,article("0","change","other",List.of(4L)),null));
        assertEquals("draft",db.queryForObject("SELECT title FROM content_article WHERE id=?",String.class,id));
        assertEquals("1",db.queryForObject("SELECT status FROM content_article WHERE id=?",String.class,id));
        assertEquals(1,revision(id));
        assertNull(db.queryForObject("SELECT publication_fact_id FROM content_article WHERE id=?",String.class,id));
        assertEquals(List.of(3L),db.queryForList("SELECT tag_id FROM content_article_tag WHERE article_id=?",Long.class,id));
        assertEquals(0,count("content_article_event"));
    }

    @Test void concurrentFirstPublicationAndEditsKeepOneFactAndMonotonicRevisions() throws Exception {
        long id=service.create(ACTOR,article("1","draft","body",List.of(3L)),null);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate=new CountDownLatch(1);
            Future<?> a=pool.submit(() -> { await(gate); service.setStatus(ACTOR,id,"0",null); });
            Future<?> b=pool.submit(() -> { await(gate); service.setStatus(ACTOR,id,"0",null); });
            gate.countDown(); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
            assertEquals(1,events(id).size()); assertEquals(2,revision(id));
            String original=events(id).get(0);
            CountDownLatch edits=new CountDownLatch(1);
            Future<?> c=pool.submit(() -> { await(edits); service.edit(ACTOR,id,article("0","edit A","a",List.of(3L)),null); });
            Future<?> d=pool.submit(() -> { await(edits); service.edit(ACTOR,id,article("0","edit B","b",List.of(4L)),null); });
            edits.countDown(); c.get(10,TimeUnit.SECONDS); d.get(10,TimeUnit.SECONDS);
            assertEquals(4,revision(id));
            assertEquals(List.of(2L,3L,4L),db.queryForList("SELECT source_revision FROM content_article_event WHERE article_id=? ORDER BY source_revision",Long.class,id));
            assertEquals(original,events(id).get(0));
        } finally { pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }

    @Test void publishingRevalidatesPersistedCategoryAndTags() {
        long id=service.create(ACTOR,article("1","draft","secret",List.of(3L)),null);
        db.update("UPDATE content_category SET status='1' WHERE id=2");
        assertEquals(400,assertThrows(ArticleFailure.class,() -> service.setStatus(ACTOR,id,"0",null)).status());
        assertEquals(1,revision(id)); assertEquals(0,count("content_article_event"));
        db.update("UPDATE content_category SET status='0' WHERE id=2");
        db.update("UPDATE content_tag SET del_flag=1 WHERE id=3");
        assertEquals(400,assertThrows(ArticleFailure.class,() -> service.setStatus(ACTOR,id,"0",null)).status());
        assertEquals(1,revision(id)); assertEquals(0,count("content_article_event"));
        assertNull(db.queryForObject("SELECT publication_fact_id FROM content_article WHERE id=?",String.class,id));
        db.update("UPDATE content_tag SET del_flag=0 WHERE id=3");
        service.setStatus(ACTOR,id,"0",null);
        assertEquals(2,revision(id)); assertEquals(1,count("content_article_event"));
    }
    @Test void longReferenceIdsRoundTripWithoutPrecisionLoss() throws Exception {
        long category=9007199254740993L, tag=9007199254740995L;
        db.update("INSERT INTO content_category(id,name,status) VALUES(?, 'Long category', '0')",category);
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(?, 'Long tag', 0)",tag);
        String body=json.writeValueAsString(java.util.Map.of("title","long","content","body","status","0",
                "isComment","1","categoryId",Long.toString(category),"tags",List.of(Long.toString(tag))));
        assertEquals(200,post("/article",AuthenticationHttpTest.token("blog",600),body).statusCode());
        assertEquals(category,db.queryForObject("SELECT category_id FROM content_article",Long.class));
        assertEquals(tag,db.queryForObject("SELECT tag_id FROM content_article_tag",Long.class));
        JsonNode event=json.readTree(db.queryForObject("SELECT payload FROM content_article_event",String.class));
        assertEquals(Long.toString(category),event.path("payload").path("category").path("id").asText());
        assertEquals(Long.toString(tag),event.path("payload").path("tags").get(0).path("id").asText());
    }
    @Test void fractionalCategoryCannotCreateAnArticle() throws Exception {
        String body="{\"title\":\"bad\",\"content\":\"body\",\"status\":\"0\",\"isComment\":\"1\",\"categoryId\":2.5,\"tags\":[3]}";
        HttpResponse<String> response=post("/article",AuthenticationHttpTest.token("blog",600),body);
        assertEquals(400,response.statusCode(),"fractional category response: "+response.body());
        assertEquals(400,json.readTree(response.body()).path("code").asInt());
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void fractionalTagCannotCreateAnArticle() throws Exception {
        String body="{\"title\":\"bad\",\"content\":\"body\",\"status\":\"0\",\"isComment\":\"1\",\"categoryId\":2,\"tags\":[3.5]}";
        HttpResponse<String> response=post("/api/blog/article",AuthenticationHttpTest.token("blog",600),body);
        assertEquals(400,response.statusCode(),"fractional tag response: "+response.body());
        assertEquals(400,json.readTree(response.body()).path("code").asInt());
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void integerReferenceNumbersRemainCompatible() throws Exception {
        String body="{\"title\":\"valid\",\"content\":\"body\",\"status\":\"0\",\"isComment\":\"1\",\"categoryId\":2,\"tags\":[3]}";
        HttpResponse<String> response=post("/article",AuthenticationHttpTest.token("blog",600),body);
        assertEquals(200,response.statusCode()); assertEquals(200,json.readTree(response.body()).path("code").asInt());
        assertEquals(2,db.queryForObject("SELECT category_id FROM content_article",Long.class));
        assertEquals(3,db.queryForObject("SELECT tag_id FROM content_article_tag",Long.class));
        assertEquals(1,count("content_article_event"));
    }
    @Test void oversizedJsonStringIsRejectedWithoutWriting() throws Exception {
        String body=json.writeValueAsString(java.util.Map.of("title","x","content","x".repeat(2000001),
                "status","0","isComment","1","categoryId","2","tags",List.of("3")));
        assertEquals(400,post("/article",AuthenticationHttpTest.token("blog",600),body).statusCode());
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void authorizationAndReferencesFailWithoutSideEffects() {
        long id=service.create(ACTOR,article("1","draft","secret",List.of(3L)),null);
        assertEquals(403,assertThrows(ArticleFailure.class,() -> service.delete("42",id,null)).status());
        assertEquals(403,assertThrows(ArticleFailure.class,() -> service.edit("42",id,article("0","stolen","body",List.of(3L)),null)).status());
        assertEquals(403,assertThrows(ArticleFailure.class,() -> service.setStatus("42",id,"0",null)).status());
        assertEquals(400,assertThrows(ArticleFailure.class,() -> service.edit(ACTOR,id,article("0","x","body",List.of(99L)),null)).status());
        assertEquals(400,assertThrows(ArticleFailure.class,() -> service.setStatus(ACTOR,id,"9",null)).status());
        assertEquals(1,revision(id)); assertEquals(0,count("content_article_event"));
        assertEquals(List.of(3L),db.queryForList("SELECT tag_id FROM content_article_tag WHERE article_id=?",Long.class,id));
        service.delete(ACTOR,id,null);
        assertEquals(0,count("content_article_event"));
    }

    @Test void publicPostUsesVerifiedAuthorAndRejectsUnauthenticatedSideEffects() throws Exception {
        String body="{\"title\":\"title\",\"content\":\"body\",\"status\":\"0\",\"isComment\":\"1\",\"categoryId\":\"2\",\"tags\":[\"3\"],\"authorId\":\"42\",\"createBy\":\"42\",\"viewCount\":999,\"sourceRevision\":999,\"publicationFactId\":\"fake\",\"isTop\":\"1\"}";
        String token=AuthenticationHttpTest.token("blog",600);
        HttpResponse<String> success=post("/api/blog/article",token,body);
        assertEquals(200,success.statusCode());
        JsonNode result=json.readTree(success.body()); assertEquals(200,result.path("code").asInt()); assertFalse(result.has("data"));
        assertEquals(ACTOR,db.queryForObject("SELECT author_id FROM content_article",String.class));
        assertEquals(ACTOR,db.queryForObject("SELECT updated_by FROM content_article",String.class));
        assertEquals("0",db.queryForObject("SELECT is_top FROM content_article",String.class));
        assertEquals(0L,db.queryForObject("SELECT view_count FROM content_article",Long.class));
        assertEquals(1L,db.queryForObject("SELECT source_revision FROM content_article",Long.class));
        assertEquals(1,count("content_article_tag")); assertEquals(1,count("content_article_event"));
        assertEquals(401,post("/article","bad",body).statusCode());
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
        assertEquals(403,post("/article",AuthenticationHttpTest.token("admin",600),body).statusCode());
        AuthenticationHttpTest.STATE.set("UNAVAILABLE");
        assertEquals(503,post("/article",token,body).statusCode());
        assertEquals(1,count("content_article")); assertEquals(1,count("content_article_tag")); assertEquals(1,count("content_article_event"));
    }
    @Test void invalidFieldsAndReferencesUseLegacyBodyWithoutWrites() throws Exception {
        String token=AuthenticationHttpTest.token("blog",600);
        for (String body : List.of(
                json.writeValueAsString(java.util.Map.of("title","", "content","body","status","0",
                        "isComment","1","categoryId","2","tags",List.of("3"))),
                json.writeValueAsString(java.util.Map.of("title","title", "content","body","status","0",
                        "isComment","1","categoryId","999","tags",List.of("3"))),
                json.writeValueAsString(java.util.Map.of("title","title", "content","body","status","0",
                        "isComment","1","categoryId","2","tags",List.of("999"))))) {
            HttpResponse<String> response=post("/api/blog/article",token,body);
            assertEquals(200,response.statusCode());
            JsonNode result=json.readTree(response.body());
            assertEquals(514,result.path("code").asInt());
            assertEquals("\u6587\u7ae0\u53c2\u6570\u4e0d\u5408\u6cd5",result.path("msg").asText());
            assertFalse(result.has("data"));
        }
        assertEquals(0,count("content_article"));
        assertEquals(0,count("content_article_tag"));
        assertEquals(0,count("content_article_event"));
    }
    @Test void exceptionMappingRetainsLegacyNotFoundAndRealAuthorizationStatuses() {
        var controller=new com.knode.content.article.ArticleController(service);
        var absent=controller.error(new ArticleFailure(404,"internal"));
        assertEquals(200,absent.getStatusCode().value());
        assertEquals(513,absent.getBody().get("code"));
        assertEquals("\u6587\u7ae0\u4e0d\u5b58\u5728",absent.getBody().get("msg"));
        for (int status : List.of(401,403,503)) {
            var response=controller.error(new ArticleFailure(status,"internal"));
            assertEquals(status,response.getStatusCode().value());
            assertEquals(status==503?500:status,response.getBody().get("code"));
        }
    }
    @Test void storageFailureUsesSafeResponseAndRollsBackEverything() throws Exception {
        db.execute("ALTER TABLE content_article_event ADD CONSTRAINT forbid_publication CHECK (event_type <> 'ArticlePublished')");
        String body=json.writeValueAsString(java.util.Map.of("title","sensitive-payload", "content","private-body",
                "status","0","isComment","1","categoryId","2","tags",List.of("3")));
        HttpResponse<String> response=post("/api/blog/article",AuthenticationHttpTest.token("blog",600),body);
        assertEquals(500,response.statusCode());
        JsonNode result=json.readTree(response.body());
        assertEquals(500,result.path("code").asInt());
        assertEquals("\u7cfb\u7edf\u9519\u8bef",result.path("msg").asText());
        assertEquals(2,result.size());
        assertFalse(response.body().contains("sensitive-payload"));
        assertFalse(response.body().contains("private-body"));
        assertFalse(response.body().contains("forbid_publication"));
        assertFalse(response.body().contains("INSERT"));
        assertEquals(0,count("content_article"));
        assertEquals(0,count("content_article_tag"));
        assertEquals(0,count("content_article_event"));
    }
    private HttpResponse<String> post(String path,String token,String body) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("token",token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return AuthenticationHttpTest.HTTP.send(request,HttpResponse.BodyHandlers.ofString());
    }
}
