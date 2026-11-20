package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.AuthFailure;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.profile.UserProfileClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
class AdminContentHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ACTOR="9007199254740993";
    static final AtomicReference<String> MODE=new AtomicReference<>("ok"), TOKEN=new AtomicReference<>();
    static final AtomicReference<List<String>> PERMS=new AtomicReference<>(List.of());
    static final AtomicBoolean PROTOCOL=new AtomicBoolean(true);
    static final AtomicInteger CALLS=new AtomicInteger();
    static final ExecutorService EXEC=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer PROVIDER=provider();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService commands;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); EXEC.shutdownNow(); }
    @BeforeEach void setup() {
        MODE.set("ok"); PERMS.set(List.of()); CALLS.set(0); PROTOCOL.set(true);
        TOKEN.set(AuthenticationHttpTest.token("admin",600));
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
        for (String t:List.of("content_article_counter_event","content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+t);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status,description) VALUES(2,'Java','0','desc'),(5,'disabled','1','x'),(6,'empty','0','x')");
        db.update("INSERT INTO content_tag(id,name,del_flag,remark) VALUES(3,'Redis',0,'note')");
    }
    long draft(String title) {
        return commands.create("42",new ArticleCommand(title,null,"1","private-body",List.of(3L),2L,"summary","1"),null);
    }
    HttpResponse<String> request(String method,String path,String body,String token,boolean forged) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null) b.header("token",token);
        if(forged) b.header("X-User-Id","1");
        if(body!=null) b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    String body(Long id,String title,String status,String top) throws Exception {
        Map<String,Object> m=new LinkedHashMap<>(); if(id!=null)m.put("id",id.toString());
        m.put("title",title); m.put("content","admin-body"); m.put("summary","summary"); m.put("categoryId","2");
        m.put("tags",List.of("3")); m.put("isTop",top); m.put("isComment","1"); m.put("status",status);
        m.put("createBy","1"); m.put("authorId","1"); m.put("viewCount","999"); m.put("publicationFactId","fake");
        return JSON.writeValueAsString(m);
    }
    @Test void fractionalArticleIdCannotMutateAnExistingArticle() throws Exception {
        long id=draft("original");
        PERMS.set(List.of("content:article:edit"));
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(body(id,"wrong target","0","0"));
        input.put("id",id+0.5);
        var response=request("PUT","/content/article",JSON.writeValueAsString(input),TOKEN.get(),false);
        assertEquals(400,response.statusCode(),"fractional id response: "+response.body());
        assertEquals(400,json(response).path("code").asInt());
        assertEquals("original",db.queryForObject("SELECT title FROM content_article WHERE id=?",String.class,id));
        assertEquals(0,count("content_article_event"));
    }
    @Test void integerArticleIdRemainsCompatible() throws Exception {
        long id=draft("original");
        PERMS.set(List.of("content:article:edit"));
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(body(id,"updated","0","0"));
        input.put("id",id);
        var response=request("PUT","/content/article",JSON.writeValueAsString(input),TOKEN.get(),false);
        assertEquals(200,response.statusCode()); assertEquals(200,json(response).path("code").asInt());
        assertEquals("updated",db.queryForObject("SELECT title FROM content_article WHERE id=?",String.class,id));
    }
    @Test void localAuthAndLivePermissionsFailClosedBeforePrivateReads() throws Exception {
        long id=draft("secret");
        assertEquals(401,request("GET","/content/article/"+id,null,null,false).statusCode());
        assertEquals(401,request("GET","/content/article/"+id,null,TOKEN.get(),true).statusCode());
        String blog=AuthenticationHttpTest.token("blog",600);
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"blog","ACTIVE",Instant.now().getEpochSecond()+1800)));
        assertEquals(403,request("GET","/content/article/"+id,null,blog,false).statusCode());
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
        PERMS.set(List.of(""));
        var denied=request("GET","/api/admin/content/article/"+id,null,TOKEN.get(),false);
        assertEquals(403,denied.statusCode()); assertFalse(denied.body().contains("private-body"));
        PERMS.set(List.of("content:article:query"));
        var allowed=request("GET","/content/article/"+id,null,TOKEN.get(),false);
        assertEquals(200,allowed.statusCode()); assertEquals("private-body",json(allowed).path("data").path("content").asText());
        PERMS.set(List.of());
        assertEquals(403,request("GET","/content/article/"+id,null,TOKEN.get(),false).statusCode());
        PERMS.set(List.of("*:*:*"));
        assertEquals(200,json(request("GET","/api/admin/content/article/"+id,null,TOKEN.get(),false)).path("code").asInt());
        assertTrue(PROTOCOL.get()); assertEquals(4,CALLS.get());
        assertEquals(1,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void localSessionFailureNeverCallsUserOrWrites() throws Exception {
        draft("secret");
        AuthenticationHttpTest.STATE.set("UNAVAILABLE");
        assertEquals(503,request("GET","/content/article/1",null,TOKEN.get(),false).statusCode());
        assertEquals(503,request("POST","/content/article",body(null,"new","0","0"),TOKEN.get(),false).statusCode());
        assertEquals(0,CALLS.get()); assertEquals(1,count("content_article")); assertEquals(0,count("content_article_event"));
    }
    @Test void authorityFailureCannotLeakOrWrite() throws Exception {
        long id=draft("secret"); String write=body(null,"new","0","0");
        for(String mode:List.of("status401","status403","status503","timeout","malformed","redirect","oversize","wrong-subject","bad-id","numeric-id","duplicate-permission","wrong-permission-type","duplicate-roles","wrong-role-type","too-many-permissions","missing-fields","body-code","code-overflow")) {
            MODE.set(mode); PERMS.set(List.of("*:*:*"));
            int expected=mode.equals("status401")?401:mode.equals("status403")?403:503;
            var read=request("GET","/content/article/"+id,null,TOKEN.get(),false);
            assertEquals(expected,read.statusCode(),mode); assertFalse(read.body().contains("private-body"),mode);
            assertFalse(read.body().contains("sensitive@example.invalid"),mode);
            var response=request("POST","/api/admin/content/article",write,TOKEN.get(),false);
            assertEquals(expected,response.statusCode(),mode); assertFalse(response.body().contains("sensitive@example.invalid"),mode);
            assertEquals(expected==503?500:expected,json(response).path("code").asInt(),mode);
            assertEquals(1,count("content_article"),mode); assertEquals(0,count("content_article_event"),mode);
        }
        var absent=new UserProfileClient("","","");
        assertEquals(503,assertThrows(AuthFailure.class,() -> absent.adminPermissions(TOKEN.get(),ACTOR)).status());
    }
    @Test void adminLifecycleKeepsAuthorAndMakesAtomicBatchDelete() throws Exception {
        long id=draft("draft");
        String created=db.queryForObject("SELECT created_at FROM content_article WHERE id=?",String.class,id);
        PERMS.set(List.of("content:article:query"));
        var detail=json(request("GET","/api/admin/content/article/"+id,null,TOKEN.get(),false)).path("data");
        assertEquals("1",detail.path("status").asText()); assertEquals("private-body",detail.path("content").asText());
        assertEquals("3",detail.path("tags").get(0).asText());
        PERMS.set(List.of("content:article:edit"));
        db.update("UPDATE content_tag SET del_flag=1 WHERE id=3");
        assertEquals(514,json(request("PUT","/content/article/"+id+"/status","{\"status\":\"0\"}",TOKEN.get(),false)).path("code").asInt());
        assertEquals("1",db.queryForObject("SELECT status FROM content_article WHERE id=?",String.class,id));
        assertEquals(0,count("content_article_event"));
        db.update("UPDATE content_tag SET del_flag=0 WHERE id=3");
        assertEquals(200,json(request("PUT","/content/article",body(id,"edited","0","1"),TOKEN.get(),false)).path("code").asInt());
        assertEquals("42",db.queryForObject("SELECT author_id FROM content_article WHERE id=?",String.class,id));
        assertEquals(ACTOR,db.queryForObject("SELECT updated_by FROM content_article WHERE id=?",String.class,id));
        assertEquals(created,db.queryForObject("SELECT created_at FROM content_article WHERE id=?",String.class,id));
        assertEquals("1",db.queryForObject("SELECT is_top FROM content_article WHERE id=?",String.class,id));
        String first=db.queryForObject("SELECT payload FROM content_article_event WHERE article_id=?",String.class,id);
        JsonNode fact=JSON.readTree(first); assertEquals(2,fact.path("schemaVersion").asInt()); assertEquals("1",fact.path("payload").path("isTop").asText());
        String factId=fact.path("publicationFactId").asText(), published=fact.path("payload").path("publishedAt").asText();
        assertEquals(200,json(request("PUT","/api/admin/content/article/"+id+"/status","{\"status\":\"1\"}",TOKEN.get(),false)).path("code").asInt());
        assertEquals(200,json(request("PUT","/content/article/"+id+"/status","{\"status\":\"0\"}",TOKEN.get(),false)).path("code").asInt());
        List<String> events=db.queryForList("SELECT payload FROM content_article_event WHERE article_id=? ORDER BY source_revision",String.class,id);
        assertEquals(first,events.get(0)); assertEquals(1,events.stream().filter(x -> x.contains("ArticlePublished")).count());
        assertEquals(factId,JSON.readTree(events.get(2)).path("publicationFactId").asText());
        assertEquals(published,JSON.readTree(events.get(2)).path("payload").path("publishedAt").asText());
        PERMS.set(List.of("content:article:add"));
        var createdResponse=json(request("POST","/api/admin/content/article",body(null,"new","1","1"),TOKEN.get(),false));
        assertEquals(200,createdResponse.path("code").asInt()); assertFalse(createdResponse.has("data"));
        long second=db.queryForObject("SELECT MAX(id) FROM content_article",Long.class);
        assertEquals(ACTOR,db.queryForObject("SELECT author_id FROM content_article WHERE id=?",String.class,second));
        PERMS.set(List.of("content:article:remove"));
        var partial=json(request("DELETE","/content/article/"+id+",99999",null,TOKEN.get(),false));
        assertEquals(513,partial.path("code").asInt()); assertEquals(0,db.queryForObject("SELECT deleted FROM content_article WHERE id=?",Integer.class,id));
        int eventsBefore=count("content_article_event");
        assertEquals(200,json(request("DELETE","/api/admin/content/article/"+second+","+id+","+id,null,TOKEN.get(),false)).path("code").asInt());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM content_article WHERE deleted=1",Integer.class));
        assertEquals(eventsBefore+1,count("content_article_event"));
    }
    @Test void adminPublishedCreateValidatesTopAndRecordsV2Snapshot() throws Exception {
        PERMS.set(List.of("content:article:add"));
        assertEquals(514,json(request("POST","/content/article",body(null,"invalid","0","2"),TOKEN.get(),false)).path("code").asInt());
        assertEquals(0,count("content_article")); assertEquals(0,count("content_article_event"));
        var response=json(request("POST","/content/article",body(null,"public","0","1"),TOKEN.get(),false));
        assertEquals(200,response.path("code").asInt()); assertFalse(response.has("data"));
        assertEquals(ACTOR,db.queryForObject("SELECT author_id FROM content_article",String.class));
        assertEquals("1",db.queryForObject("SELECT is_top FROM content_article",String.class));
        JsonNode event=JSON.readTree(db.queryForObject("SELECT payload FROM content_article_event",String.class));
        assertEquals(2,event.path("schemaVersion").asInt()); assertEquals("1",event.path("payload").path("isTop").asText());
        assertEquals(ACTOR,event.path("payload").path("authorId").asText());
    }
    @Test void adminListFiltersLiteralAndCategoriesRespectPermissionMatrix() throws Exception {
        long a=draft("100% literal"), b=draft("100X other"), c=draft("under_score");
        db.update("UPDATE content_article SET created_at=? WHERE id IN (?,?,?)",java.sql.Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")),a,b,c);
        PERMS.set(List.of("content:article:list"));
        JsonNode page=json(request("GET","/api/admin/content/article/list?title=%25",null,TOKEN.get(),false)).path("data");
        assertEquals("1",page.path("total").asText()); assertEquals(Long.toString(a),page.path("rows").get(0).path("id").asText());
        assertFalse(page.toString().contains("private-body"));
        JsonNode underscore=json(request("GET","/content/article/list?title=%5F",null,TOKEN.get(),false)).path("data");
        assertEquals("1",underscore.path("total").asText()); assertEquals(Long.toString(c),underscore.path("rows").get(0).path("id").asText());
        assertEquals(514,json(request("GET","/content/article/list?pageSize=101",null,TOKEN.get(),false)).path("code").asInt());
        assertEquals("0",json(request("GET","/content/article/list?categoryId=0",null,TOKEN.get(),false)).path("data").path("total").asText());
        assertEquals("0",json(request("GET","/content/article/list?categoryId=-1",null,TOKEN.get(),false)).path("data").path("total").asText());
        JsonNode all=json(request("GET","/content/article/list?pageSize=2",null,TOKEN.get(),false)).path("data");
        assertEquals("3",all.path("total").asText()); assertEquals(Long.toString(c),all.path("rows").get(0).path("id").asText());
        assertEquals("2025-01-02 08:00:00",all.path("rows").get(0).path("createTime").asText());
        assertEquals("42",all.path("rows").get(0).path("createBy").asText());
        assertEquals(403,request("GET","/content/category/listAllCategory",null,TOKEN.get(),false).statusCode());
        for(String permission:List.of("content:article:query","content:article:add","content:article:edit","content:category:list")) {
            PERMS.set(List.of(permission));
            JsonNode categories=json(request("GET","/content/category/listAllCategory",null,TOKEN.get(),false)).path("data");
            assertEquals(2,categories.size(),permission); assertEquals("6",categories.get(1).path("id").asText());
        }
        PERMS.set(List.of("content:article:query"));
        assertEquals(403,request("GET","/api/admin/content/category/list",null,TOKEN.get(),false).statusCode());
        PERMS.set(List.of("content:category:list"));
        JsonNode categoryPage=json(request("GET","/api/admin/content/category/list?pageSize=2",null,TOKEN.get(),false)).path("data");
        assertEquals("3",categoryPage.path("total").asText()); assertEquals(2,categoryPage.path("rows").size());
        assertTrue(PROTOCOL.get());
    }
    @Test void legacyV1FactIsUnchangedWhenAdminAddsV2Projection() throws Exception {
        long id=commands.create("42",new ArticleCommand("old",null,"1","body",List.of(3L),2L,"s","0"),null);
        String raw=db.queryForObject("SELECT payload FROM content_article_event WHERE article_id=?",String.class,id);
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(raw);
        old.put("schemaVersion",1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)old.path("payload")).remove("isTop");
        String v1=JSON.writeValueAsString(old);
        db.update("UPDATE content_article_event SET payload=? WHERE article_id=?",v1,id);
        PERMS.set(List.of("content:article:edit"));
        assertEquals(200,json(request("PUT","/content/article",body(id,"edited","0","1"),TOKEN.get(),false)).path("code").asInt());
        List<String> events=db.queryForList("SELECT payload FROM content_article_event WHERE article_id=? ORDER BY source_revision",String.class,id);
        assertEquals(v1,events.get(0));
        assertEquals(2,JSON.readTree(events.get(1)).path("schemaVersion").asInt());
        assertEquals("1",JSON.readTree(events.get(1)).path("payload").path("isTop").asText());
    }
    @Test void batchFailureDuringEventInsertRollsBackEntireBatch() throws Exception {
        long a=commands.create("42",new ArticleCommand("first",null,"1","body",List.of(3L),2L,"s","0"),null);
        long b=commands.create("42",new ArticleCommand("second",null,"1","body",List.of(3L),2L,"s","0"),null);
        int prior=count("content_article_event");
        db.execute("ALTER TABLE content_article_event ADD CONSTRAINT deny_second CHECK (event_type <> 'ArticleDeleted' OR article_id <> "+b+")");
        PERMS.set(List.of("content:article:remove"));
        var response=request("DELETE","/content/article/"+a+","+b,null,TOKEN.get(),false);
        assertEquals(500,response.statusCode()); assertEquals(500,json(response).path("code").asInt());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_article WHERE deleted=1",Integer.class));
        assertEquals(prior,count("content_article_event"));
    }
    @Test void longArticleIdIsPreservedForAdminReadAndEdit() throws Exception {
        long id=9007199254740993L;
        db.update("INSERT INTO content_article(id,title,content,category_id,is_top,is_comment,status,author_id,created_at,updated_at,updated_by,source_revision,deleted) VALUES(?, 'long','private',2,'0','1','1','42',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'42',1,0)",id);
        db.update("INSERT INTO content_article_tag(article_id,tag_id) VALUES(?,3)",id);
        PERMS.set(List.of("content:article:query"));
        var detail=json(request("GET","/content/article/"+id,null,TOKEN.get(),false)).path("data");
        assertTrue(detail.path("id").isTextual()); assertEquals(Long.toString(id),detail.path("id").asText());
        PERMS.set(List.of("content:article:edit"));
        assertEquals(200,json(request("PUT","/api/admin/content/article",body(id,"large","1","1"),TOKEN.get(),false)).path("code").asInt());
        assertEquals("42",db.queryForObject("SELECT author_id FROM content_article WHERE id=?",String.class,id));
        assertEquals(ACTOR,db.queryForObject("SELECT updated_by FROM content_article WHERE id=?",String.class,id));
    }
    private static HttpServer provider() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(EXEC);
            server.createContext("/api/admin/auth/me",e -> {
                CALLS.incrementAndGet();
                try {
                    if (!"GET".equals(e.getRequestMethod()) || !Objects.equals(TOKEN.get(),e.getRequestHeaders().getFirst("token"))
                            || e.getRequestHeaders().getFirst("Authorization")!=null) PROTOCOL.set(false);
                    String mode=MODE.get();
                    if(mode.startsWith("status")) { reply(e,Integer.parseInt(mode.substring(6)),"{}"); return; }
                    if(mode.equals("redirect")) { e.getResponseHeaders().add("Location","http://127.0.0.1:1/"); reply(e,302,"{}"); return; }
                    if(mode.equals("timeout")) Thread.sleep(1300);
                    if(mode.equals("malformed")) { reply(e,200,"{"); return; }
                    if(mode.equals("oversize")) { reply(e,200,"x".repeat(3145729)); return; }
                    if(mode.equals("missing-fields")) { reply(e,200,"{}"); return; }
                    Object permissions=mode.equals("wrong-permission-type")?List.of(123):mode.equals("duplicate-permission")?List.of("*:*:*","*:*:*"):mode.equals("too-many-permissions")?java.util.stream.IntStream.range(0,1001).mapToObj(i -> "p"+i).toList():PERMS.get();
                    String subject=mode.equals("wrong-subject")?"42":mode.equals("bad-id")?"9223372036854775808":ACTOR;
                    Map<String,Object> user=Map.of("id",mode.equals("numeric-id")?42:subject,"nickName","Alice","avatar","x","sex","1","email","sensitive@example.invalid");
                    Object code=mode.equals("code-overflow")?4294967496L:mode.equals("body-code")?500:200;
                    Map<String,Object> root=Map.of("code",code,"msg","ok",
                        "data",Map.of("roles",mode.equals("duplicate-roles")?List.of("admin","admin"):mode.equals("wrong-role-type")?List.of(123):List.of("admin"),"permissions",permissions,"user",user));
                    reply(e,200,JSON.writeValueAsString(root));
                } catch(Exception ex) { e.close(); }
            });
            server.start(); return server;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange e,int status,String body) throws Exception {
        byte[] data=body.getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(status,data.length);
        try(var output=e.getResponseBody()) { output.write(data); }
    }
}
