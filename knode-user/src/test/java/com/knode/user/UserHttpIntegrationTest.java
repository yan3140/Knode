package com.knode.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.user.auth.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired RevocationCoordinator coordinator;
    @MockitoBean SessionStore.Reader reader;
    @MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    final Map<String,SessionStore.Session> sessions=new ConcurrentHashMap<>();
    final AtomicBoolean failRead=new AtomicBoolean(), failWrite=new AtomicBoolean(), failMark=new AtomicBoolean();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        Path privateKey=Files.createTempFile("knode-test-private-", ".pem"), publicKey=Files.createTempFile("knode-test-public-", ".pem");
        privateKey.toFile().deleteOnExit();publicKey.toFile().deleteOnExit();
        Files.writeString(privateKey,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:knode_user_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username",() -> "sa");registry.add("spring.datasource.password",() -> "");
        registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");
        registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",() -> privateKey.toString());
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",() -> publicKey.toString());
    }
    @BeforeAll void schema() { new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource); }
    @BeforeEach void setup() {
        for (String table:new String[]{"auth_revocation_intent","sys_role_menu","sys_user_role","yan_menu","sys_role","sys_user"})
            jdbc.update("DELETE FROM "+table);
        sessions.clear(); failRead.set(false);failWrite.set(false);failMark.set(false);
        reset(reader,writer);
        when(reader.find(anyString())).thenAnswer(inv -> {
            if (failRead.get()) throw new SessionStore.Unavailable("simulated read");
            return Optional.ofNullable(sessions.get(inv.getArgument(0)));
        });
        doAnswer(inv -> {
            if (failWrite.get()) throw new SessionStore.Unavailable("simulated create");
            SessionStore.Session s=inv.getArgument(0);
            if (sessions.putIfAbsent(s.sid(),s)!=null) throw new SessionStore.Unavailable("collision");
            return null;
        }).when(writer).create(any());
        doAnswer(inv -> {
            if (failWrite.get()) throw new SessionStore.Unavailable("simulated delete");
            sessions.remove((String)inv.getArgument(0));
            if (failMark.getAndSet(false)) jdbc.execute("ALTER TABLE auth_revocation_intent RENAME TO auth_revocation_temp");
            return null;
        }).when(writer).revoke(anyString());
        String password=encoder.encode("test-password");
        for (Object[] row:new Object[][]{{42,"alice","Alice","0","0",0},{43,"bob","Bob","0","0",0},
                {44,"admin","Admin","1","0",0},{45,"disabled","Disabled","0","1",0},{46,"deleted","Deleted","0","0",1}})
            jdbc.update("INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(?,?,?,?,?,?,?,'2',?)",
                    row[0],row[1],row[2],password,row[1]+"@example.invalid",row[3],row[4],row[5]);
        jdbc.update("INSERT INTO sys_role(id,role_name,role_key,role_sort,status,del_flag) VALUES (10,'Editors','editor',1,'0','0'),(11,'Disabled','disabled',2,'1','0')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(44,10),(44,11)");
        jdbc.update("INSERT INTO yan_menu(id,parent_id,menu_name,order_num,menu_type,status,visible,del_flag,perms) VALUES (100,0,'Root',1,'M','0','0','0',NULL),(101,100,'Users',1,'C','0','0','0','system:user:list'),(102,100,'Edit',2,'F','0','0','0','system:user:edit'),(103,100,'Disabled',3,'C','1','0','0','should:not:appear'),(104,100,'Other',4,'C','0','0','0','disabled:role')");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(10,100),(10,101),(10,102),(10,103),(11,104)");
    }
    ResponseEntity<String> call(HttpMethod method,String path,String body,String token) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if (token!=null) headers.add("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
    }
    JsonNode data(ResponseEntity<String> response) throws Exception { return json.readTree(response.getBody()); }
    String login(String user,String scope) throws Exception {
        var response=call(HttpMethod.POST,"/api/"+scope+"/auth/login","{\"userName\":\""+user+"\",\"password\":\"test-password\"}",null);
        assertEquals(200,response.getStatusCode().value());assertEquals(200,data(response).get("code").asInt());
        return data(response).path("data").path("token").asText();
    }
    @Test void credentialsAccountStateAndActualRbac() throws Exception {
        var bad=call(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"wrong\"}",null);
        assertEquals(505,data(bad).get("code").asInt());
        for (String name:List.of("disabled","deleted")) {
            var r=call(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\""+name+"\",\"password\":\"test-password\"}",null);
            assertEquals(505,data(r).get("code").asInt());
        }
        var forbidden=call(HttpMethod.POST,"/api/admin/auth/login","{\"userName\":\"alice\",\"password\":\"test-password\"}",null);
        assertEquals(403,forbidden.getStatusCode().value());
        String admin=login("admin","admin");
        var adminLogin=call(HttpMethod.POST,"/api/admin/auth/login","{\"userName\":\"admin\",\"password\":\"test-password\"}",null);
        assertEquals(1,data(adminLogin).path("data").size());assertTrue(data(adminLogin).path("data").has("token"));
        var blogLogin=call(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"test-password\"}",null);
        assertEquals(2,data(blogLogin).path("data").size());assertEquals("42",data(blogLogin).path("data").path("userInfo").path("id").asText());
        var me=data(call(HttpMethod.GET,"/api/admin/auth/me",null,admin)).path("data");
        assertEquals("44",me.path("user").path("id").asText());
        assertEquals(List.of("editor"),json.convertValue(me.path("roles"),json.getTypeFactory().constructCollectionType(List.class,String.class)));
        assertEquals(2,me.path("permissions").size());assertFalse(me.path("permissions").toString().contains("disabled:role"));
        var menus=data(call(HttpMethod.GET,"/api/admin/auth/routers",null,admin)).path("data").path("menus");
        assertEquals("100",menus.get(0).path("id").asText());assertEquals("101",menus.get(0).path("children").get(0).path("id").asText());
        assertEquals(1,menus.get(0).path("children").size());
        assertEquals(403,call(HttpMethod.GET,"/api/blog/user/userInfo",null,admin).getStatusCode().value());
        jdbc.update("UPDATE sys_user SET status='1' WHERE id=44");
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,admin).getStatusCode().value());
    }
    @Test void registrationAndProfileIgnorePrivilegeAndOtherUserFields() throws Exception {
        String request="{\"userName\":\"new\",\"nickName\":\"New\",\"password\":\"example-password\",\"email\":\"new@example.invalid\",\"id\":1,\"type\":\"1\",\"status\":\"1\",\"roleIds\":[10],\"createBy\":1}";
        var registered=call(HttpMethod.POST,"/api/blog/user/register",request,null);
        assertEquals(200,data(registered).get("code").asInt());assertFalse(data(registered).has("data"));
        var row=jdbc.queryForMap("SELECT id,type,status,del_flag,create_by,password FROM sys_user WHERE user_name='new'");
        assertNotEquals(1L,((Number)row.get("id")).longValue());assertEquals("0",row.get("type"));assertEquals("0",row.get("status"));
        assertNull(row.get("create_by"));assertTrue(encoder.matches("example-password",(String)row.get("password")));
        var duplicate=call(HttpMethod.POST,"/api/blog/user/register","{\"userName\":\"another\",\"nickName\":\"Another\",\"password\":\"x\",\"email\":\"new@example.invalid\"}",null);
        assertEquals(200,duplicate.getStatusCode().value());assertEquals(503,data(duplicate).get("code").asInt());
        String alice=login("alice","blog");
        var info=data(call(HttpMethod.GET,"/api/blog/user/userInfo?userId=43",null,alice)).path("data");
        assertTrue(info.path("id").isTextual());assertEquals("42",info.path("id").asText());
        var update=call(HttpMethod.PUT,"/api/blog/user/userInfo","{\"id\":43,\"nickName\":\"Updated\",\"avatar\":\"/test.png\",\"sex\":\"1\",\"email\":\"evil@example.invalid\",\"type\":\"1\",\"status\":\"1\"}",alice);
        assertEquals(200,data(update).get("code").asInt());assertFalse(data(update).has("data"));
        assertEquals("Updated",jdbc.queryForObject("SELECT nick_name FROM sys_user WHERE id=42",String.class));
        assertEquals("Bob",jdbc.queryForObject("SELECT nick_name FROM sys_user WHERE id=43",String.class));
        assertEquals("alice@example.invalid",jdbc.queryForObject("SELECT email FROM sys_user WHERE id=42",String.class));
        assertEquals("0",jdbc.queryForObject("SELECT type FROM sys_user WHERE id=42",String.class));
    }
    @Test void sessionsAndFaultsDoNotPretendLogoutSucceeded() throws Exception {
        String a=login("alice","blog"), b=login("alice","blog");
        assertEquals(200,call(HttpMethod.POST,"/api/blog/auth/logout","{}",a).getStatusCode().value());
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,a).getStatusCode().value());
        assertEquals(200,call(HttpMethod.GET,"/api/blog/user/userInfo",null,b).getStatusCode().value());
        String c=login("alice","blog"); failWrite.set(true);
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"test-password\"}",null).getStatusCode().value());
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",c).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE state='PENDING'",Integer.class));
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,c).getStatusCode().value());
        failWrite.set(false);assertEquals(1,coordinator.recoverPending(10));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE state='PENDING'",Integer.class));
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,c).getStatusCode().value());
        failRead.set(true);
        assertEquals(503,call(HttpMethod.GET,"/api/blog/user/userInfo",null,b).getStatusCode().value());
        failRead.set(false);
    }
    @Test void databaseFailureWindowsNeverReturnSuccess() throws Exception {
        String token=login("alice","blog");
        jdbc.execute("ALTER TABLE auth_revocation_intent RENAME TO auth_revocation_temp");
        try { assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",token).getStatusCode().value()); }
        finally { jdbc.execute("ALTER TABLE auth_revocation_temp RENAME TO auth_revocation_intent"); }
        assertEquals(200,call(HttpMethod.GET,"/api/blog/user/userInfo",null,token).getStatusCode().value());
        failMark.set(true);
        try { assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",token).getStatusCode().value()); }
        finally { jdbc.execute("ALTER TABLE auth_revocation_temp RENAME TO auth_revocation_intent"); }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE state='PENDING'",Integer.class));
        assertEquals(1,coordinator.recoverPending(10));
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,token).getStatusCode().value());
    }
    @Test void rejectsForgedOrAmbiguousHeadersAndMaintainsHealthSemantics() throws Exception {
        String token=login("alice","blog");
        var headers=new HttpHeaders();headers.add("token",token);headers.add("token",token);
        var duplicate=http.exchange("/api/blog/user/userInfo",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(401,duplicate.getStatusCode().value());
        headers=new HttpHeaders();headers.add("token",token);headers.add("X-User-Id","43");
        var forged=http.exchange("/api/blog/user/userInfo",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(401,forged.getStatusCode().value());
        assertEquals(200,call(HttpMethod.GET,"/health/live",null,null).getStatusCode().value());
        failRead.set(true);
        assertEquals(503,call(HttpMethod.GET,"/health/ready",null,null).getStatusCode().value());
        assertEquals(401,call(HttpMethod.GET,"/api/blog/auth/login",null,null).getStatusCode().value());
    }

    @Test void independentBlogAndAdminSessionsAndDuplicateRegistrationCodes() throws Exception {
        String blog=login("admin","blog"), admin=login("admin","admin");
        assertEquals(200,call(HttpMethod.POST,"/api/blog/auth/logout","{}",blog).getStatusCode().value());
        assertEquals(401,call(HttpMethod.GET,"/api/admin/auth/me",null,blog).getStatusCode().value());
        assertEquals(200,call(HttpMethod.GET,"/api/admin/auth/me",null,admin).getStatusCode().value());
        var duplicate=call(HttpMethod.POST,"/api/blog/user/register","{\"userName\":\"alice\",\"nickName\":\"Unique\",\"password\":\"x\",\"email\":\"unique@example.invalid\"}",null);
        assertEquals(501,data(duplicate).path("code").asInt());
        var duplicateNick=call(HttpMethod.POST,"/api/blog/user/register","{\"userName\":\"unique\",\"nickName\":\"Alice\",\"password\":\"x\",\"email\":\"unique@example.invalid\"}",null);
        assertEquals(512,data(duplicateNick).path("code").asInt());
    }
    @Test void databaseFailureAndMalformedSessionAreUnavailable() throws Exception {
        String token=login("alice","blog");
        jdbc.execute("ALTER TABLE sys_user RENAME TO sys_user_temp");
        try {
            assertEquals(503,call(HttpMethod.GET,"/api/blog/user/userInfo",null,token).getStatusCode().value());
            assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"test-password\"}",null).getStatusCode().value());
        } finally { jdbc.execute("ALTER TABLE sys_user_temp RENAME TO sys_user"); }
        failRead.set(true);
        var unavailable=call(HttpMethod.GET,"/api/blog/user/userInfo",null,token);
        assertEquals(503,unavailable.getStatusCode().value());assertEquals(500,data(unavailable).path("code").asInt());
    }
}
