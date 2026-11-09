package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import com.knode.content.profile.UserProfileClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
class PublicContentHttpTest {
    static final ObjectMapper J=new ObjectMapper();
    static final AtomicReference<String> MODE=new AtomicReference<>("ok");
    static final AtomicInteger TOKEN_CALLS=new AtomicInteger(), PROFILE_CALLS=new AtomicInteger();
    static final AtomicBoolean PROTOCOL_OK=new AtomicBoolean(true);
    static final AtomicReference<List<String>> REQUESTED=new AtomicReference<>(List.of());
    static final ExecutorService EXEC=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer PROVIDER=provider();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ArticleCommandService commands;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); EXEC.shutdownNow(); }
    @BeforeEach void setup() {
        MODE.set("ok"); TOKEN_CALLS.set(0); PROFILE_CALLS.set(0); PROTOCOL_OK.set(true);
        for (String t:List.of("content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+t);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status,description) VALUES(2,'Java','0','category desc'),(5,'disabled','1','x'),(6,'empty','0','x')");
        db.update("INSERT INTO content_tag(id,name,del_flag,remark) VALUES(3,'Redis',0,'tag note'),(4,'Hidden',1,'x')");
    }
    long create(String author,String status,String title) {
        return commands.create(author,new ArticleCommand(title,null,"1","secret-body-"+title,List.of(3L),2L,"summary",status),null);
    }
    HttpResponse<String> get(String path) throws Exception {
        return AuthenticationHttpTest.HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(String path) throws Exception {
        var r=get(path); assertEquals(200,r.statusCode()); JsonNode n=J.readTree(r.body()); assertEquals(200,n.path("code").asInt()); return n.path("data");
    }

    @Test void anonymousViewsPaginationAndProfilesUseOneBatch() throws Exception {
        long first=create("42","0","first"), third=create("42","0","third"), second=create("43","0","second"), draft=create("42","1","draft");
        long withdrawn=create("42","0","withdrawn"); commands.setStatus("42",withdrawn,"1",null);
        long deleted=create("42","0","deleted"); commands.delete("42",deleted,null);
        db.update("UPDATE content_article SET is_top='1',view_count=7,created_at=? WHERE id=?",Timestamp.from(Instant.parse("2025-01-02T00:00:00Z")),first);
        db.update("UPDATE content_article SET view_count=5,created_at=? WHERE id IN (?,?)",Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")),second,third);
        JsonNode page=data("/api/blog/article/articleList?pageNum=1&pageSize=10&categoryId=0");
        assertEquals(Set.of("rows","total"),fieldNames(page)); assertEquals("3",page.path("total").asText());
        JsonNode rows=page.path("rows"); assertEquals(3,rows.size());
        assertEquals(Long.toString(first),rows.get(0).path("id").asText());
        assertEquals("2025-01-02 08:00:00",rows.get(0).path("createTime").asText());
        assertEquals("7",rows.get(0).path("viewCount").asText());
        assertEquals("FOUND",rows.get(0).path("authorInfoStatus").asText());
        assertEquals("Alice",rows.get(0).path("authorName").asText());
        assertEquals("MISSING",rows.get(1).path("authorInfoStatus").asText());
        assertTrue(rows.get(1).path("authorName").isNull());
        assertFalse(rows.toString().contains("secret-body"));
        assertEquals(1,TOKEN_CALLS.get()); assertEquals(1,PROFILE_CALLS.get()); assertTrue(PROTOCOL_OK.get());
        assertEquals(List.of("42","43"),REQUESTED.get());
        assertEquals(Long.toString(second),data("/article/articleList?pageNum=2&pageSize=1").path("rows").get(0).path("id").asText());
        assertEquals("3",data("/article/articleList?categoryId=2").path("total").asText());
        assertEquals("3",data("/article/articleList?categoryId=-1").path("total").asText());
        assertEquals("0",data("/article/articleList?categoryId=6").path("total").asText());
        int tokenBefore=TOKEN_CALLS.get(); data("/article/articleList?categoryId=6"); assertEquals(tokenBefore,TOKEN_CALLS.get());
        JsonNode detail=data("/article/"+first); assertEquals("secret-body-first",detail.path("content").asText());
        for(long id:List.of(draft,withdrawn,deleted,999L)) {
            JsonNode missing=J.readTree(get("/api/blog/article/"+id).body());
            assertEquals(513,missing.path("code").asInt()); assertFalse(missing.toString().contains("secret-body"));
        }
        JsonNode hot=data("/article/hotArticleList");
        assertEquals(Long.toString(first),hot.get(0).path("id").asText());
        assertEquals(Set.of("id","title","viewCount"),fieldNames(hot.get(0)));
        JsonNode cats=data("/category/getCategoryList"); assertEquals(1,cats.size()); assertEquals("category desc",cats.get(0).path("description").asText());
        JsonNode tags=data("/api/blog/tag/listAllTag"); assertEquals(1,tags.size()); assertEquals("tag note",tags.get(0).path("remark").asText());
        assertEquals(514,J.readTree(get("/article/articleList?pageSize=101").body()).path("code").asInt());
        assertEquals(514,J.readTree(get("/article/articleList?pageNum=bad").body()).path("code").asInt());
    }
    @Test void hotLimitsTenAndUsesPersistedCounts() throws Exception {
        for (int i=0;i<12;i++) {
            long id=create("42","0","hot"+i);
            db.update("UPDATE content_article SET view_count=? WHERE id=?",i,id);
        }
        JsonNode hot=data("/api/blog/article/hotArticleList");
        assertEquals(10,hot.size()); assertEquals("11",hot.get(0).path("viewCount").asText());
        assertEquals("2",hot.get(9).path("viewCount").asText());
        assertEquals(0,TOKEN_CALLS.get()); assertEquals(0,PROFILE_CALLS.get());
    }
    @Test void precisePublicAllowlistDoesNotExposeWritesOrUnknownPaths() throws Exception {
        create("42","0","one");
        assertEquals(401,get("/article/private").statusCode());
        assertEquals(401,get("/api/admin/content/article/1").statusCode());
        HttpRequest write=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/blog/article"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(401,AuthenticationHttpTest.HTTP.send(write,HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest forged=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/article/articleList"))
            .header("X-User-Id","42").GET().build();
        assertEquals(401,AuthenticationHttpTest.HTTP.send(forged,HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpRequest invalidToken=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/article/articleList"))
            .header("token","not-a-token").GET().build();
        assertEquals(200,AuthenticationHttpTest.HTTP.send(invalidToken,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(PROTOCOL_OK.get());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM content_article",Integer.class));
    }
    @Test void providerFailuresAndProtocolViolationsAreUnavailableNotMissing() throws Exception {
        long id=create("42","0","one");
        for(String mode:List.of("bad-token","token401","token403","token503","token-audience","token-permission","token-type","token-string-ttl","missing-item","extra-item","wrong-type","extra-field","bad-date","bad-revision","bad-json","truncated","too-large","status401","status403","status503","redirect","timeout")) {
            MODE.set(mode);
            JsonNode row=data("/article/"+id);
            assertEquals("UNAVAILABLE",row.path("authorInfoStatus").asText(),mode);
            assertTrue(row.path("authorName").isNull(),mode);
            assertEquals("secret-body-one",row.path("content").asText(),mode);
            assertFalse(row.toString().contains("synthetic-secret"),mode);
        }
    }
    @Test void longIdAndEmptyResultPreservePublicSerialization() throws Exception {
        long id=9007199254740993L;
        create("42","0","small");
        db.update("INSERT INTO content_article(id,title,content,category_id,is_top,is_comment,status,author_id,created_at,updated_at,updated_by,source_revision,deleted) VALUES(?, 'large','private',2,'1','1','0','42',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'42',1,0)",id);
        JsonNode page=data("/api/blog/article/articleList?pageSize=10");
        assertTrue(page.path("total").isTextual()); assertEquals("2",page.path("total").asText());
        JsonNode first=page.path("rows").get(0);
        assertTrue(first.path("id").isTextual()); assertEquals(Long.toString(id),first.path("id").asText());
        assertTrue(first.path("viewCount").isTextual()); assertTrue(first.path("categoryId").isTextual());
        assertEquals(514,J.readTree(get("/article/9223372036854775808").body()).path("code").asInt());
        assertEquals(514,J.readTree(get("/article/999999999999999999999999").body()).path("code").asInt());
        assertEquals(513,J.readTree(get("/article/-1").body()).path("code").asInt());
    }
    @Test void hundredMultibyteProfilesFitBoundedBatchResponse() {
        MODE.set("bulk");
        var client=new UserProfileClient("http://127.0.0.1:"+PROVIDER.getAddress().getPort(),"knode-content","synthetic-secret");
        List<String> ids=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(Integer::toString).toList();
        var profiles=client.lookup(ids);
        assertEquals(100,profiles.size());
        assertTrue(profiles.values().stream().allMatch(p -> "FOUND".equals(p.status()) && p.nickName().length()==2048 && p.avatar().length()==2048));
        assertEquals(1,TOKEN_CALLS.get()); assertEquals(1,PROFILE_CALLS.get());
    }
    @Test void originMustBeHttpsOrLiteralLoopbackAndNoCredentialsOrPaths() {
        for(String origin:List.of("http://localhost:8080","http://example.org","https://user:pass@example.org","https://example.org/path","https://example.org/?x=1","https://example.org/#x"))
            assertThrows(IllegalArgumentException.class,() -> new UserProfileClient(origin,"knode-content","synthetic-secret"),origin);
        assertDoesNotThrow(() -> new UserProfileClient("http://127.0.0.1:12345","knode-content","synthetic-secret"));
        var bad=assertThrows(IllegalArgumentException.class,() -> new UserProfileClient("https://secret:password@bad host","knode-content","synthetic-secret"));
        assertFalse(bad.getMessage().contains("password"));
        var disabled=new UserProfileClient("","","");
        assertTrue(disabled.lookup(List.of()).isEmpty());
        assertEquals("UNAVAILABLE",disabled.lookup(List.of("42")).get("42").status());
    }
    private static Set<String> fieldNames(JsonNode n) { Set<String> names=new HashSet<>(); n.fieldNames().forEachRemaining(names::add); return names; }
    private static HttpServer provider() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(EXEC);
            server.createContext("/internal/v1/auth/service-tokens", exchange -> {
                TOKEN_CALLS.incrementAndGet();
                try {
                    String expected="Basic "+Base64.getEncoder().encodeToString("knode-content:synthetic-secret".getBytes(StandardCharsets.UTF_8));
                    JsonNode body=J.readTree(exchange.getRequestBody());
                    if (!"POST".equals(exchange.getRequestMethod()) || !expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            || !"knode-user".equals(body.path("audience").asText()) || body.size()!=2
                            || body.path("permissions").size()!=1 || !"users:public:read".equals(body.path("permissions").get(0).asText())) PROTOCOL_OK.set(false);
                    if (MODE.get().startsWith("token") && MODE.get().length()==8 && Character.isDigit(MODE.get().charAt(5))) {
                        reply(exchange,Integer.parseInt(MODE.get().substring(5)),"{}"); return;
                    }
                    if ("bad-token".equals(MODE.get())) { reply(exchange,200,"{\"accessToken\":\"bad\",\"tokenType\":\"Bearer\",\"expiresIn\":0,\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\"]}"); return; }
                    Map<String,Object> token=new LinkedHashMap<>();
                    token.put("accessToken","fixture-token"); token.put("tokenType",MODE.get().equals("token-type")?"Basic":"Bearer");
                    token.put("expiresIn",MODE.get().equals("token-string-ttl")?"120":120);
                    token.put("audience",MODE.get().equals("token-audience")?"other":"knode-user");
                    token.put("permissions",List.of(MODE.get().equals("token-permission")?"users:state:read":"users:public:read"));
                    reply(exchange,200,J.writeValueAsString(token));
                } catch (Exception e) { exchange.close(); }
            });
            server.createContext("/internal/v1/users/public-profiles:batch", exchange -> {
                PROFILE_CALLS.incrementAndGet();
                try {
                    if (!"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) PROTOCOL_OK.set(false);
                    JsonNode body=J.readTree(exchange.getRequestBody());
                    if (body.size()!=1 || !body.path("userIds").isArray()) PROTOCOL_OK.set(false);
                    List<String> requested=new ArrayList<>(); body.path("userIds").forEach(id -> requested.add(id.asText())); REQUESTED.set(requested);
                    String mode=MODE.get();
                    if (mode.startsWith("status")) { reply(exchange,Integer.parseInt(mode.substring(6)),"{}"); return; }
                    if (mode.equals("redirect")) { exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/"); reply(exchange,302,"{}"); return; }
                    if (mode.equals("timeout")) Thread.sleep(1300);
                    if (mode.equals("bad-json")) { reply(exchange,200,"{"); return; }
                    if (mode.equals("truncated")) {
                        exchange.sendResponseHeaders(200,64);
                        exchange.getResponseBody().write(new byte[]{123,125});
                        exchange.close(); return;
                    }
                    if (mode.equals("too-large")) { reply(exchange,200,"x".repeat(3145729)); return; }
                    List<Map<String,Object>> items=new ArrayList<>();
                    for(JsonNode id:body.path("userIds")) {
                        if ("42".equals(id.asText()) || mode.equals("bulk")) {
                            Map<String,Object> found=new LinkedHashMap<>();
                            found.put("userId",mode.equals("wrong-type")?42:id.asText()); found.put("status","FOUND");
                            found.put("nickName",mode.equals("bulk")?"\u591a".repeat(2048):"Alice"); found.put("avatar",mode.equals("bulk")?"\u591a".repeat(2048):null); found.put("profileRevision",mode.equals("bad-revision")?"9223372036854775808":"1"); found.put("updatedAt",mode.equals("bad-date")?"bad":"2026-01-01T00:00:00Z");
                            if (mode.equals("extra-field")) found.put("email","should-not-leak");
                            items.add(found);
                        } else items.add(Map.of("userId",id.asText(),"status","MISSING"));
                    }
                    if (mode.equals("missing-item")) items.clear();
                    if (mode.equals("extra-item")) items.add(Map.of("userId","999","status","MISSING"));
                    ObjectMapper output=mode.equals("bulk")?J.copy():J;
                    if (mode.equals("bulk")) output.getFactory().configure(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII,true);
                    reply(exchange,200,output.writeValueAsString(Map.of("items",items)));
                } catch (Exception e) { exchange.close(); }
            });
            server.start(); return server;
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange e,int status,String text) throws IOException {
        byte[] data=text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().add("Content-Type","application/json");
        e.sendResponseHeaders(status,data.length); try(var out=e.getResponseBody()) { out.write(data); }
    }
}
