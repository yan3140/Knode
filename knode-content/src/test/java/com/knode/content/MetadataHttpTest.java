package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
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
class MetadataHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ACTOR="9007199254740993";
    static final AtomicReference<List<String>> PERMS=new AtomicReference<>(List.of("*:*:*"));
    static final AtomicReference<String> TOKEN=new AtomicReference<>();
    static final AtomicBoolean PROTOCOL=new AtomicBoolean(true);
    static final ExecutorService EXEC=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer PROVIDER=provider();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); EXEC.shutdownNow(); }
    @BeforeEach void setup() {
        TOKEN.set(AuthenticationHttpTest.token("admin",600)); PERMS.set(List.of("*:*:*")); PROTOCOL.set(true);
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
        for(String table:List.of("content_article_counter_event","content_comment_event","content_comment","content_comment_limit","content_metadata_target","content_metadata_job","content_link","content_write_fence","content_article_event","content_article_tag","content_article","content_tag","content_category"))
            db.execute("DROP TABLE IF EXISTS "+table);
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,remark,del_flag) VALUES(3,'base','base remark',0)");
    }
    HttpResponse<String> request(String method,String path,Object body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(token!=null) b.header("token",token);
        if(body!=null) b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
    @Test void tagsEnforcePermissionsAuditAndParenthesizedSearch() throws Exception {
        assertEquals(401,request("POST","/content/tag",Map.of("name","red","remark","alpha"),null).statusCode());
        PERMS.set(List.of());
        assertEquals(403,request("POST","/content/tag",Map.of("name","red","remark","alpha"),TOKEN.get()).statusCode());
        PERMS.set(List.of("content:tag:add"));
        assertEquals(506,json(request("POST","/content/tag",Map.of("name","red","remark",""),TOKEN.get())).path("code").asInt());
        assertEquals(515,json(request("POST","/content/tag",Map.of("name","x".repeat(257),"remark","r"),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/content/tag",Map.of("name","red","remark","alpha","createBy","1","delFlag",1),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/api/admin/content/tag",Map.of("name","bad","remark","needle"),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/content/tag",Map.of("name","misc","remark","needle"),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/content/tag",Map.of("name","literal%_!","remark","plain"),TOKEN.get())).path("code").asInt());
        long red=db.queryForObject("SELECT id FROM content_tag WHERE name='red'",Long.class);
        long bad=db.queryForObject("SELECT id FROM content_tag WHERE name='bad'",Long.class);
        assertEquals(ACTOR,db.queryForObject("SELECT created_by FROM content_tag WHERE id=?",String.class,red));
        PERMS.set(List.of("content:tag:remove"));
        assertEquals(515,json(request("DELETE","/content/tag/"+red+",99999",null,TOKEN.get())).path("code").asInt());
        assertEquals(0,db.queryForObject("SELECT del_flag FROM content_tag WHERE id=?",Integer.class,red));
        assertEquals(200,json(request("DELETE","/api/admin/content/tag/"+bad+","+bad,null,TOKEN.get())).path("code").asInt());
        PERMS.set(List.of("content:tag:list"));
        JsonNode rows=json(request("GET","/api/admin/content/tag/list?name=red&remark=needle",null,TOKEN.get())).path("data");
        assertEquals("2",rows.path("total").asText());
        assertFalse(rows.toString().contains("bad"));
        assertEquals("1",json(request("GET","/content/tag/list?name=%25_!",null,TOKEN.get())).path("data").path("total").asText());
        assertEquals(403,request("GET","/content/tag/"+red,null,TOKEN.get()).statusCode());
        for(String permission:List.of("content:tag:list","content:article:add","content:article:edit","content:article:query")) {
            PERMS.set(List.of(permission));
            assertTrue(json(request("GET","/content/tag/listAllTag",null,TOKEN.get())).path("data").isArray());
        }
        PERMS.set(List.of());
        assertEquals(403,request("GET","/content/tag/listAllTag",null,TOKEN.get()).statusCode());
        PERMS.set(List.of("content:tag:query"));
        JsonNode tag=json(request("GET","/content/tag/"+red,null,TOKEN.get())).path("data");
        assertEquals(ACTOR,tag.path("createBy").asText()); assertTrue(tag.path("id").isTextual());
        assertFalse(json(request("GET","/content/tag/99999",null,TOKEN.get())).has("data"));
        PERMS.set(List.of("content:tag:edit"));
        assertEquals(515,json(request("PUT","/content/tag",Map.of("name","x"),TOKEN.get())).path("code").asInt());
        for(Object invalid:List.of("abc","0","1.5",1.5,"9223372036854775808")) {
            assertEquals(515,json(request("PUT","/content/tag",Map.of("id",invalid,"name","x"),TOKEN.get())).path("code").asInt());
        }
        assertEquals(515,json(request("PUT","/content/tag",Map.of("id","99999","name","x"),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("PUT","/api/admin/content/tag",Map.of("id",Long.toString(red),"name","new","createBy","1"),TOKEN.get())).path("code").asInt());
        assertEquals(ACTOR,db.queryForObject("SELECT updated_by FROM content_tag WHERE id=?",String.class,red));
        assertEquals(200,json(request("PUT","/content/tag",Map.of("id",Long.toString(red),"name","","remark","updated"),TOKEN.get())).path("code").asInt());
        assertEquals("new",db.queryForObject("SELECT name FROM content_tag WHERE id=?",String.class,red));
        PERMS.set(List.of("content:tag:add"));
        assertEquals(200,json(request("POST","/content/tag",Map.of("name","new","remark","duplicate allowed"),TOKEN.get())).path("code").asInt());
        long big=9007199254740993L;
        db.update("INSERT INTO content_tag(id,name,remark,del_flag,created_by) VALUES(?,'big','note',0,?)",big,ACTOR);
        PERMS.set(List.of("content:tag:query"));
        JsonNode large=json(request("GET","/content/tag/"+big,null,TOKEN.get())).path("data");
        assertTrue(large.path("id").isTextual()); assertEquals(Long.toString(big),large.path("id").asText());
        assertTrue(PROTOCOL.get());
    }
    @Test void linksValidateUrlsAndExposeOnlyApprovedEntries() throws Exception {
        assertEquals(401,request("POST","/api/blog/link/getAllLink",null,null).statusCode());
        assertEquals(401,request("POST","/content/link",Map.of("name","bad","address","https://bad.invalid"),null).statusCode());
        var spoof=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/link/getAllLink")).header("X-User-Id","1").GET().build();
        assertEquals(401,AuthenticationHttpTest.HTTP.send(spoof,HttpResponse.BodyHandlers.ofString()).statusCode());
        PERMS.set(List.of());
        assertEquals(403,request("POST","/content/link",Map.of("name","bad","address","https://bad.invalid"),TOKEN.get()).statusCode());
        PERMS.set(List.of("content:link:add"));
        for(String url:List.of("javascript:alert(1)","data:text/plain,x","https://user:pass@example.invalid","https://example.invalid/\r\nX")) {
            assertEquals(515,json(request("POST","/content/link",Map.of("name","bad","address",url),TOKEN.get())).path("code").asInt());
        }
        assertEquals(515,json(request("POST","/content/link",Map.of("name","bad","address","https://bad.invalid","status","9"),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/content/link",Map.of("name","pending","address","https://pending.invalid","createBy","1","delFlag",1),TOKEN.get())).path("code").asInt());
        assertEquals(200,json(request("POST","/api/admin/content/link",Map.of("name","approved","address","https://approved.invalid","logo","https://cdn.invalid/a?sig=x","status","0"),TOKEN.get())).path("code").asInt());
        long pending=db.queryForObject("SELECT id FROM content_link WHERE name='pending'",Long.class);
        long approved=db.queryForObject("SELECT id FROM content_link WHERE name='approved'",Long.class);
        assertEquals("2",db.queryForObject("SELECT status FROM content_link WHERE id=?",String.class,pending));
        assertEquals(ACTOR,db.queryForObject("SELECT created_by FROM content_link WHERE id=?",String.class,pending));
        JsonNode visible=json(request("GET","/api/blog/link/getAllLink",null,null)).path("data");
        assertEquals(1,visible.size()); assertEquals(Long.toString(approved),visible.get(0).path("id").asText());
        assertFalse(visible.toString().contains("createBy")); assertFalse(visible.toString().contains("status"));
        PERMS.set(List.of("content:link:edit"));
        for(Object invalid:List.of("abc","0",1.5,"9223372036854775808")) {
            assertEquals(515,json(request("PUT","/content/link",Map.of("id",invalid,"name","x"),TOKEN.get())).path("code").asInt());
        }
        assertEquals(200,json(request("PUT","/content/link/changeLinkStatus",Map.of("id",Long.toString(pending),"status","0"),TOKEN.get())).path("code").asInt());
        assertEquals(2,json(request("GET","/link/getAllLink",null,null)).path("data").size());
        assertEquals(ACTOR,db.queryForObject("SELECT updated_by FROM content_link WHERE id=?",String.class,pending));
        assertEquals(515,json(request("PUT","/content/link",Map.of("id",Long.toString(pending),"logo","data:image/png,x"),TOKEN.get())).path("code").asInt());
        PERMS.set(List.of("content:link:list"));
        JsonNode page=json(request("GET","/content/link/list?status=0",null,TOKEN.get())).path("data");
        assertEquals("2",page.path("total").asText()); assertTrue(page.path("rows").get(0).path("id").isTextual());
        PERMS.set(List.of("content:link:remove"));
        assertEquals(515,json(request("DELETE","/content/link/"+pending+",99999",null,TOKEN.get())).path("code").asInt());
        assertEquals(0,db.queryForObject("SELECT del_flag FROM content_link WHERE id=?",Integer.class,pending));
        assertEquals(200,json(request("DELETE","/api/admin/content/link/"+approved+","+approved,null,TOKEN.get())).path("code").asInt());
        assertEquals(1,json(request("GET","/link/getAllLink",null,null)).path("data").size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM content_article_event",Integer.class));
        long big=9007199254740993L;
        db.update("INSERT INTO content_link(id,name,address,status,del_flag,created_by,created_at,updated_by,updated_at) VALUES(?,'big','https://big.invalid','1',0,?,CURRENT_TIMESTAMP(6),?,CURRENT_TIMESTAMP(6))",big,ACTOR,ACTOR);
        PERMS.set(List.of("content:link:query"));
        JsonNode large=json(request("GET","/content/link/"+big,null,TOKEN.get())).path("data");
        assertTrue(large.path("id").isTextual()); assertEquals(Long.toString(big),large.path("id").asText());
        assertEquals(1,json(request("GET","/link/getAllLink",null,null)).path("data").size());
        assertTrue(PROTOCOL.get());
    }
    private static HttpServer provider() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(EXEC);
            server.createContext("/api/admin/auth/me",e -> {
                try {
                    if(!"GET".equals(e.getRequestMethod()) || !Objects.equals(TOKEN.get(),e.getRequestHeaders().getFirst("token"))
                            || e.getRequestHeaders().getFirst("Authorization")!=null) PROTOCOL.set(false);
                    byte[] data=JSON.writeValueAsBytes(Map.of("code",200,"msg","ok","data",
                            Map.of("roles",List.of("admin"),"permissions",PERMS.get(),"user",Map.of("id",ACTOR))));
                    e.sendResponseHeaders(200,data.length); try(var out=e.getResponseBody()) { out.write(data); }
                } catch(Exception failure) { e.close(); }
            });
            server.start(); return server;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
}
