package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class CommentHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ACTOR="9007199254740993";
    static final AtomicReference<String> MODE=new AtomicReference<>("ok");
    static final AtomicInteger TOKENS=new AtomicInteger(), BATCHES=new AtomicInteger(), MAX_IDS=new AtomicInteger();
    static final AtomicBoolean PROTOCOL=new AtomicBoolean(true);
    static final AtomicReference<CountDownLatch> PROFILE_ENTERED=new AtomicReference<>(),PROFILE_RELEASE=new AtomicReference<>();
    static final ExecutorService SERVER_THREADS=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer PROVIDER=provider();
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService articles;
    @LocalServerPort int port;

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); SERVER_THREADS.shutdownNow(); }
    @BeforeEach void setup() {
        MODE.set("ok"); TOKENS.set(0); BATCHES.set(0); MAX_IDS.set(0); PROTOCOL.set(true);
        PROFILE_ENTERED.set(null); PROFILE_RELEASE.set(null); session(ACTOR,"blog");
        for(String t:List.of("content_article_counter_event","content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+t);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,remark,del_flag) VALUES(3,'Tag','note',0)");
    }
    void session(String actor,String scope) {
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,actor,scope,"ACTIVE",Instant.now().getEpochSecond()+1800)));
    }
    String token(String actor,String scope) {
        Instant now=Instant.now();
        return Jwts.builder().header().keyId("test-key").and().issuer("knode-user").subject(actor).audience().add("knode-api").and()
                .claim("sid",AuthenticationHttpTest.SID).claim("scope",scope).claim("token_use","user").id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(5))).expiration(Date.from(now.plusSeconds(600))).signWith(AuthenticationHttpTest.KEYS.getPrivate()).compact();
    }
    long article(String status,String isComment,String title) {
        return articles.create("42",new ArticleCommand(title,null,isComment,"article-body",List.of(3L),2L,"summary",status),null);
    }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    Map<String,Object> body(Object type,Object article,Object root,Object target,Object toUser,String content) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("type",type); if(article!=null)out.put("articleId",article);out.put("rootId",root);
        out.put("toCommentId",target);out.put("toCommentUserId",toUser);out.put("content",content);return out;
    }
    HttpResponse<String> request(String method,String path,Object body,String token,boolean forged,String forwarded) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null)b.header("token",token);
        if(forged)b.header("X-User-Id","42");
        if(forwarded!=null)b.header("X-Forwarded-For",forwarded);
        if(body!=null)b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
    HttpResponse<String> post(Map<String,Object> body,String actor) throws Exception {
        session(actor,"blog"); return request("POST","/comment",body,token(actor,"blog"),false,null);
    }
    long latestComment() { return db.queryForObject("SELECT MAX(id) FROM content_comment",Long.class); }
    JsonNode event(long id) throws Exception { return JSON.readTree(db.queryForObject("SELECT payload FROM content_comment_event WHERE comment_id=?",String.class,id)); }

    @Test void authorizationValidationAndTrustedRootEvent() throws Exception {
        long article=article("0","1","visible"); String blog=token(ACTOR,"blog");
        Map<String,Object> input=body(0,Long.toString(article),-1,-1,-1,"hello");
        assertEquals(401,request("POST","/api/blog/comment",input,null,false,null).statusCode());
        assertEquals(401,request("POST","/comment",input,blog,true,null).statusCode());
        session(ACTOR,"admin");
        assertEquals(403,request("POST","/comment",input,token(ACTOR,"admin"),false,null).statusCode());
        session(ACTOR,"blog"); AuthenticationHttpTest.STATE.set(null);
        assertEquals(401,request("POST","/comment",input,blog,false,null).statusCode());
        session(ACTOR,"blog");
        assertEquals(506,json(request("POST","/comment",body(0,article,-1,-1,-1,"  "),blog,false,null)).path("code").asInt());
        assertEquals(515,json(request("POST","/comment",body(0,article,-1,1,-1,"bad"),blog,false,null)).path("code").asInt());
        assertEquals(515,json(request("POST","/comment",body(0,article,-1,-1,-1,"x".repeat(4001)),blog,false,null)).path("code").asInt());
        assertEquals(400,request("POST","/comment",body(0,article,1.5,1,-1,"bad"),blog,false,null).statusCode());
        input.put("id","5");input.put("createBy","42");input.put("delFlag",1);input.put("createTime","fake");
        var success=request("POST","/api/blog/comment",input,blog,false,null);
        assertEquals(200,success.statusCode()); assertEquals(200,json(success).path("code").asInt());assertFalse(json(success).has("data"));
        long id=latestComment(); assertEquals(ACTOR,db.queryForObject("SELECT actor_id FROM content_comment WHERE id=?",String.class,id));
        JsonNode fact=event(id); assertEquals("CommentCreated",fact.path("eventType").asText());
        assertEquals(Long.toString(id),fact.path("aggregateId").asText()); assertEquals("1",fact.path("sourceRevision").asText());
        assertEquals("42",fact.path("payload").path("receiverId").asText()); assertEquals(ACTOR,fact.path("payload").path("actorId").asText());
        assertEquals(1,db.queryForObject("SELECT source_revision FROM content_article WHERE id=?",Integer.class,article));
        assertEquals(1,count("content_article_event")); assertEquals(1,count("content_comment_event"));
        for(String path:List.of("/comment/commentList?articleId="+article,"/api/blog/comment/commentList?articleId="+article)) {
            var read=request("GET",path,null,null,false,null); assertEquals(200,read.statusCode());
            assertEquals("1",json(read).path("data").path("total").asText());
        }
        assertEquals(401,request("GET","/comment",null,null,false,null).statusCode());
        assertEquals(401,request("POST","/comment/commentList",input,null,false,null).statusCode());
        assertEquals(401,request("POST","/comment/linkCommentList",input,null,false,null).statusCode());
        assertEquals(401,request("GET","/comment/commentList?articleId="+article,null,null,true,null).statusCode());
        assertEquals(515,json(request("GET","/comment/commentList?articleId="+article+"&pageSize=101",null,null,false,null)).path("code").asInt());
        assertEquals(515,json(request("GET","/comment/commentList?articleId="+article+"&pageNum=0",null,null,false,null)).path("code").asInt());
        assertTrue(PROTOCOL.get());
    }
    @Test void repliesMustTargetTheSameVisibleThreadAndRealRecipient() throws Exception {
        long a=article("0","1","a"),b=article("0","1","b");
        assertEquals(200,json(post(body("0",a,-1,-1,-1,"root-a"),ACTOR)).path("code").asInt()); long root=latestComment();
        assertEquals(200,json(post(body("0",b,-1,-1,-1,"root-b"),ACTOR)).path("code").asInt()); long other=latestComment();
        assertEquals(200,json(post(body("1",1,-1,-1,-1,"friend"),ACTOR)).path("code").asInt()); long friend=latestComment();
        assertEquals(200,json(post(body("0",a,root,root,ACTOR,"reply-1"),"77")).path("code").asInt());long reply=latestComment();
        assertEquals(ACTOR,event(reply).path("payload").path("receiverId").asText());
        assertEquals(200,json(post(body("0",a,root,reply,"77","reply-2"),ACTOR)).path("code").asInt());
        long nested=latestComment();assertEquals("77",event(nested).path("payload").path("receiverId").asText());
        assertEquals(515,json(post(body("0",a,root,reply,"42","spoof"),ACTOR)).path("code").asInt());
        assertEquals(515,json(post(body("0",a,root,other,ACTOR,"cross-article"),ACTOR)).path("code").asInt());
        assertEquals(515,json(post(body("0",a,root,friend,ACTOR,"cross-type"),ACTOR)).path("code").asInt());
        assertEquals(515,json(post(body("0",a,other,reply,"77","wrong-root"),ACTOR)).path("code").asInt());
        JsonNode rows=json(request("GET","/comment/commentList?articleId="+a,null,null,false,null)).path("data").path("rows");
        assertEquals(1,rows.size()); assertEquals(2,rows.get(0).path("children").size());
        assertEquals(Long.toString(reply),rows.get(0).path("children").get(0).path("id").asText());
        assertEquals(Long.toString(nested),rows.get(0).path("children").get(1).path("id").asText());
        assertEquals(5,count("content_comment"));assertEquals(5,count("content_comment_event"));
    }
    @Test void deletedTargetsCannotBeRepliedToAndOmittedReceiverIsDerived() throws Exception {
        long a=article("0","1","targets");
        post(body(0,a,-1,-1,-1,"deleted-root"),ACTOR);long deletedRoot=latestComment();
        db.update("UPDATE content_comment SET deleted=1 WHERE id=?",deletedRoot);
        assertEquals(515,json(post(body(0,a,deletedRoot,deletedRoot,ACTOR,"invalid-root"),"77")).path("code").asInt());
        post(body(0,a,-1,-1,-1,"live-root"),ACTOR);long root=latestComment();
        assertEquals(200,json(post(body(0,a,root,root,null,"derived"),"77")).path("code").asInt());
        long reply=latestComment();assertEquals(ACTOR,event(reply).path("payload").path("receiverId").asText());
        assertEquals(ACTOR,db.queryForObject("SELECT to_user_id FROM content_comment WHERE id=?",String.class,reply));
        db.update("UPDATE content_comment SET deleted=1 WHERE id=?",reply);
        assertEquals(515,json(post(body(0,a,root,reply,"77","invalid-target"),ACTOR)).path("code").asInt());
        assertEquals(3,count("content_comment"));assertEquals(3,count("content_comment_event"));
    }
    @Test void friendSentinelsNormalizeToIndependentSpace() throws Exception {
        long article=article("0","1","unrelated");
        for(Object sentinel:Arrays.asList(null,0,1)) {
            assertEquals(200,json(post(body(1,sentinel,-1,-1,-1,"friend-"+sentinel),ACTOR)).path("code").asInt());
            long id=latestComment();
            assertNull(db.queryForObject("SELECT article_id FROM content_comment WHERE id=?",Long.class,id));
            JsonNode fact=event(id); assertEquals("LinkCommentCreated",fact.path("eventType").asText());
            assertTrue(fact.path("payload").path("articleId").isNull()); assertTrue(fact.path("payload").path("receiverId").isNull());
        }
        long root=latestComment();
        assertEquals(200,json(post(body(1,"1",root,root,ACTOR,"friend-reply"),"77")).path("code").asInt());
        assertEquals("LinkCommentReplied",event(latestComment()).path("eventType").asText());
        assertTrue(event(latestComment()).path("payload").path("receiverId").isNull());
        assertEquals(515,json(post(body(1,2,-1,-1,-1,"invalid"),ACTOR)).path("code").asInt());
        assertEquals(515,json(request("GET","/comment/linkCommentList?articleId=2",null,null,false,null)).path("code").asInt());
        for(String path:List.of("/comment/linkCommentList?articleId=1","/api/blog/comment/linkCommentList?articleId=0")) {
            JsonNode data=json(request("GET",path,null,null,false,null)).path("data");
            assertEquals("3",data.path("total").asText()); assertEquals(3,data.path("rows").size());
        }
        assertEquals(1,count("content_article_event")); assertEquals(4,count("content_comment_event"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_comment WHERE article_id=?",Integer.class,article));
    }
    @Test void hiddenOrDisabledArticlesCannotBeCommentedOnOrRead() throws Exception {
        long draft=article("1","1","draft"),disabled=article("0","0","disabled"),hidden=article("0","1","hidden");
        assertEquals(513,json(post(body(0,draft,-1,-1,-1,"draft"),ACTOR)).path("code").asInt());
        var denied=post(body(0,disabled,-1,-1,-1,"disabled"),ACTOR);assertEquals(403,denied.statusCode());assertEquals(403,json(denied).path("code").asInt());
        assertEquals(200,json(post(body(0,hidden,-1,-1,-1,"private-after-withdraw"),ACTOR)).path("code").asInt());
        articles.setStatus("42",hidden,"1",null);
        assertEquals(513,json(post(body(0,hidden,-1,-1,-1,"withdrawn"),ACTOR)).path("code").asInt());
        var response=request("GET","/comment/commentList?articleId="+hidden,null,null,false,null);
        assertEquals(513,json(response).path("code").asInt());assertFalse(response.body().contains("private-after-withdraw"));
        articles.delete("42",hidden,null);
        assertEquals(513,json(request("GET","/comment/commentList?articleId="+hidden,null,null,false,null)).path("code").asInt());
        assertEquals(513,json(post(body(0,999999,-1,-1,-1,"missing"),ACTOR)).path("code").asInt());
        assertEquals(1,count("content_comment")); assertEquals(1,count("content_comment_event"));
    }
    @Test void largeIdsRemainExactAcrossWriteListAndEvent() throws Exception {
        long articleId=9007199254740995L,commentId=9007199254740993L;
        db.update("INSERT INTO content_article(id,title,content,category_id,is_top,is_comment,status,author_id,created_at,updated_at,updated_by,view_count,counter_revision,source_revision,publication_fact_id,published_at,deleted) VALUES(?,'large','body',2,'0','1','0','42',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'42',0,0,1,?,CURRENT_TIMESTAMP(6),0)",articleId,UUID.randomUUID().toString());
        db.execute("ALTER TABLE content_comment ALTER COLUMN id RESTART WITH 9007199254740993");
        var response=post(body("0",Long.toString(articleId),"-1","-1","-1","large-id"),ACTOR);
        assertEquals(200,json(response).path("code").asInt());
        assertEquals(commentId,latestComment());
        JsonNode row=json(request("GET","/comment/commentList?articleId="+articleId,null,null,false,null)).path("data").path("rows").get(0);
        assertTrue(row.path("id").isTextual());assertEquals(Long.toString(commentId),row.path("id").asText());
        assertEquals(Long.toString(articleId),row.path("articleId").asText());assertEquals(ACTOR,row.path("createBy").asText());
        assertEquals(Long.toString(articleId),event(commentId).path("payload").path("articleId").asText());
        assertEquals(400,post(body("0","9223372036854775808",-1,-1,-1,"overflow"),ACTOR).statusCode());
    }
    @Test void expiredLimitCleanupIsBoundedPerRequest() throws Exception {
        Timestamp expired=Timestamp.from(Instant.now().minusSeconds(60));
        for(int i=0;i<105;i++) db.update("INSERT INTO content_comment_limit(kind,key_hash,hits,expires_at) VALUES('U',?,1,?)",String.format("%064x",i),expired);
        assertEquals(200,json(post(body(1,1,-1,-1,-1,"cleanup"),ACTOR)).path("code").asInt());
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM content_comment_limit WHERE expires_at<=CURRENT_TIMESTAMP(6)",Integer.class));
    }
    @Test void profileBatchDegradesAndEmptyPagesDoNotCallUser() throws Exception {
        long article=article("0","1","profiles");
        assertEquals("0",json(request("GET","/comment/commentList?articleId="+article,null,null,false,null)).path("data").path("total").asText());
        assertEquals(0,TOKENS.get());assertEquals(0,BATCHES.get());
        post(body(0,article,-1,-1,-1,"one"),"42");post(body(0,article,-1,-1,-1,"two"),"77");
        MODE.set("missing");
        JsonNode rows=json(request("GET","/comment/commentList?articleId="+article,null,null,false,null)).path("data").path("rows");
        assertEquals("FOUND",rows.get(0).path("userInfoStatus").asText());assertEquals("MISSING",rows.get(1).path("userInfoStatus").asText());
        assertEquals(1,TOKENS.get());assertEquals(1,BATCHES.get());assertEquals(2,MAX_IDS.get());
        MODE.set("unavailable");
        rows=json(request("GET","/comment/commentList?articleId="+article,null,null,false,null)).path("data").path("rows");
        assertEquals("UNAVAILABLE",rows.get(0).path("userInfoStatus").asText());assertEquals("UNAVAILABLE",rows.get(1).path("userInfoStatus").asText());
        MODE.set("slow");long start=System.nanoTime();
        rows=json(request("GET","/comment/commentList?articleId="+article,null,null,false,null)).path("data").path("rows");
        assertEquals("UNAVAILABLE",rows.get(0).path("userInfoStatus").asText());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3500);assertTrue(PROTOCOL.get());
    }
    @Test void profileCallDoesNotHoldTheArticleFence() throws Exception {
        long a=article("0","1","concurrent-read");
        assertEquals(200,json(post(body(0,a,-1,-1,-1,"visible-before-withdraw"),ACTOR)).path("code").asInt());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        PROFILE_ENTERED.set(entered);PROFILE_RELEASE.set(release);MODE.set("hold");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> read=pool.submit(() -> request("GET","/comment/commentList?articleId="+a,null,null,false,null));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?> withdraw=pool.submit(() -> articles.setStatus("42",a,"1",null));
            withdraw.get(750,TimeUnit.MILLISECONDS);
            assertFalse(read.isDone(),"profile response must still be held while withdrawal commits");
            release.countDown();
            var oldSnapshot=read.get(5,TimeUnit.SECONDS);
            assertEquals(200,json(oldSnapshot).path("code").asInt());assertTrue(oldSnapshot.body().contains("visible-before-withdraw"));
            MODE.set("ok");
            var after=request("GET","/comment/commentList?articleId="+a,null,null,false,null);
            assertEquals(513,json(after).path("code").asInt());assertFalse(after.body().contains("visible-before-withdraw"));
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void duplicateUserAndIpWindowsAreSharedAndExpire() throws Exception {
        long article=article("0","1","rate");Map<String,Object> same=body(0,article,-1,-1,-1,"repeat");
        assertEquals(200,json(post(same,ACTOR)).path("code").asInt());
        var duplicate=post(same,ACTOR);assertEquals(429,duplicate.statusCode());
        assertTrue(Integer.parseInt(duplicate.headers().firstValue("Retry-After").orElseThrow())>=1);
        assertEquals(1,count("content_comment"));
        db.update("UPDATE content_comment_limit SET expires_at=? WHERE kind='D'",Timestamp.from(Instant.now().minusSeconds(1)));
        assertEquals(200,json(post(same,ACTOR)).path("code").asInt()); assertEquals(2,count("content_comment"));
        List<Map<String,Object>> limits=db.queryForList("SELECT kind,key_hash,hits FROM content_comment_limit");
        assertTrue(limits.stream().allMatch(m -> m.get("KEY_HASH").toString().matches("[0-9a-f]{64}")));
        assertFalse(limits.toString().contains("repeat"));assertFalse(limits.toString().contains("127.0.0.1"));
        db.update("DELETE FROM content_comment_limit");
        for(int i=0;i<10;i++) assertEquals(200,json(post(body(1,1,-1,-1,-1,"user-"+i),ACTOR)).path("code").asInt());
        var userLimited=post(body(1,1,-1,-1,-1,"user-over"),ACTOR);assertEquals(429,userLimited.statusCode());
        db.update("UPDATE content_comment_limit SET expires_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        assertEquals(200,json(post(body(1,1,-1,-1,-1,"user-over"),ACTOR)).path("code").asInt());
    }
    @Test void peerLimitIgnoresUntrustedForwardedFor() throws Exception {
        for(int user=0;user<3;user++) {
            String actor=Long.toString(100+user);session(actor,"blog");String token=token(actor,"blog");
            for(int i=0;i<10;i++) {
                var response=request("POST","/comment",body(1,0,-1,-1,-1,"ip-"+user+"-"+i),token,false,"198.51.100."+(i+1));
                assertEquals(200,json(response).path("code").asInt());
            }
        }
        session("200","blog");
        var limited=request("POST","/comment",body(1,0,-1,-1,-1,"ip-over"),token("200","blog"),false,"203.0.113.1");
        assertEquals(429,limited.statusCode());assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())>0);
        assertEquals(30,count("content_comment"));assertEquals(30,count("content_comment_event"));
    }
    @Test void rollbackAndLimiterFailureNeverPersistAComment() throws Exception {
        long a=article("0","1","rollback");
        db.execute("ALTER TABLE content_comment_event ADD CONSTRAINT forbid_comment CHECK (event_type <> 'CommentCreated')");
        var failed=post(body(0,a,-1,-1,-1,"one"),ACTOR);assertEquals(500,failed.statusCode());
        assertEquals(0,count("content_comment"));assertEquals(0,count("content_comment_event"));
        db.execute("DROP TABLE content_comment_limit");
        var unavailable=post(body(0,a,-1,-1,-1,"two"),ACTOR);assertEquals(503,unavailable.statusCode());
        assertEquals(500,json(unavailable).path("code").asInt());assertEquals(0,count("content_comment"));
    }
    @Test void largeThreadIsExplicitlyRejectedOnReadButDoesNotBlockWrites() throws Exception {
        long a=article("0","1","large");post(body(0,a,-1,-1,-1,"root"),ACTOR);long root=latestComment();
        for(int i=0;i<500;i++) db.update("INSERT INTO content_comment(comment_type,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at,deleted) VALUES('0',?,?,?,?,?,'fixture',CURRENT_TIMESTAMP(6),0)",a,root,root,ACTOR,"77");
        assertEquals(200,json(post(body(0,a,root,root,ACTOR,"one more"),"77")).path("code").asInt());
        var response=request("GET","/comment/commentList?articleId="+a+"&pageSize=1",null,null,false,null);
        assertEquals(413,response.statusCode());assertEquals(515,json(response).path("code").asInt());
        assertEquals(502,count("content_comment"));assertEquals(0,TOKENS.get());assertEquals(0,BATCHES.get());
    }
    @Test void fullRootPageKeepsRepliesAndCapsProfileLookup() throws Exception {
        long a=article("0","1","page");
        for(int i=1;i<=100;i++) db.update("INSERT INTO content_comment(comment_type,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at,deleted) VALUES('0',?,-1,-1,'-1',?,'root',CURRENT_TIMESTAMP(6),0)",a,Integer.toString(i));
        long root=db.queryForObject("SELECT MIN(id) FROM content_comment",Long.class);
        db.update("INSERT INTO content_comment(comment_type,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at,deleted) VALUES('0',?,?,?,'1','101','reply',CURRENT_TIMESTAMP(6),0)",a,root,root);
        var response=request("GET","/comment/commentList?articleId="+a+"&pageSize=100",null,null,false,null);
        assertEquals(200,response.statusCode());JsonNode data=json(response).path("data");
        assertEquals(100,data.path("rows").size());assertEquals(1,data.path("rows").get(0).path("children").size());
        assertTrue(data.path("profileLookupLimited").asBoolean());
        assertEquals("UNAVAILABLE",data.path("rows").get(0).path("children").get(0).path("userInfoStatus").asText());
        assertEquals(100,MAX_IDS.get()); assertEquals(1,BATCHES.get());
    }
    @Test void previewIsUnicodeSafeAndListKeepsOriginal() throws Exception {
        long a=article("0","1","unicode");
        String emoji=new String(Character.toChars(0x1f600));
        String content="<img src=x onerror=alert(1)>"+emoji.repeat(501);
        assertEquals(200,json(post(body(0,a,-1,-1,-1,content),ACTOR)).path("code").asInt());long id=latestComment();
        assertEquals(content,db.queryForObject("SELECT content FROM content_comment WHERE id=?",String.class,id));
        String preview=event(id).path("payload").path("contentPreview").asText();
        assertEquals(500,preview.codePointCount(0,preview.length()));assertFalse(Character.isHighSurrogate(preview.charAt(preview.length()-1)));
        JsonNode row=json(request("GET","/comment/commentList?articleId="+a,null,null,false,null)).path("data").path("rows").get(0);
        assertEquals(content,row.path("content").asText());
    }
    private static HttpServer provider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(SERVER_THREADS);
            server.createContext("/internal/v1/auth/service-tokens",e -> {
                try {
                    TOKENS.incrementAndGet();
                    if(!"POST".equals(e.getRequestMethod()) || !Objects.toString(e.getRequestHeaders().getFirst("Authorization"),"").startsWith("Basic ")) PROTOCOL.set(false);
                    reply(e,200,JSON.writeValueAsBytes(Map.of("accessToken","fixture-token","tokenType","Bearer","expiresIn",120,"audience","knode-user","permissions",List.of("users:public:read"))));
                } catch(Exception ignored) { e.close(); }
            });
            server.createContext("/internal/v1/users/public-profiles:batch",e -> {
                try {
                    BATCHES.incrementAndGet();if(!"Bearer fixture-token".equals(e.getRequestHeaders().getFirst("Authorization")))PROTOCOL.set(false);
                    JsonNode ids=JSON.readTree(e.getRequestBody()).path("userIds");MAX_IDS.accumulateAndGet(ids.size(),Math::max);
                    if("slow".equals(MODE.get())) Thread.sleep(1300);
                    if("hold".equals(MODE.get())) {
                        CountDownLatch entered=PROFILE_ENTERED.get(),release=PROFILE_RELEASE.get();
                        if(entered!=null)entered.countDown();if(release!=null)release.await(3,TimeUnit.SECONDS);
                    }
                    if("unavailable".equals(MODE.get())) { e.sendResponseHeaders(503,-1);e.close();return; }
                    List<Map<String,Object>> items=new ArrayList<>();
                    for(JsonNode node:ids) {
                        String id=node.asText();
                        if("missing".equals(MODE.get()) && "77".equals(id)) items.add(Map.of("userId",id,"status","MISSING"));
                        else items.add(Map.of("userId",id,"status","FOUND","nickName","name-"+id,"avatar","fixture","profileRevision","1","updatedAt","2025-01-01T00:00:00Z"));
                    }
                    reply(e,200,JSON.writeValueAsBytes(Map.of("items",items)));
                } catch(Exception ignored) { e.close(); }
            });
            server.start();return server;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange e,int status,byte[] data) throws Exception {
        e.sendResponseHeaders(status,data.length);try(var out=e.getResponseBody()) { out.write(data); }
    }
}
