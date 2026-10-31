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
    @Autowired UserTokens tokens;
    @MockitoBean SessionStore.Reader reader;
    @MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    static java.security.KeyPair oldMachineKey;
    @Autowired com.knode.user.auth.SigningKeyRing signingKeys;
    final Map<String,SessionStore.Session> sessions=new ConcurrentHashMap<>();
    final AtomicBoolean failRead=new AtomicBoolean(), failWrite=new AtomicBoolean(), failMark=new AtomicBoolean();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        oldMachineKey=generator.generateKeyPair();
        Path oldPublic=Files.createTempFile("knode-test-old-", ".pem");oldPublic.toFile().deleteOnExit();
        Files.writeString(oldPublic,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(oldMachineKey.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        Path privateKey=Files.createTempFile("knode-test-private-", ".pem"), publicKey=Files.createTempFile("knode-test-public-", ".pem");
        privateKey.toFile().deleteOnExit();publicKey.toFile().deleteOnExit();
        Files.writeString(privateKey,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        Path trusted=Files.createTempFile("knode-trusted-", ".json"), policy=Files.createTempFile("knode-policy-", ".json");
        trusted.toFile().deleteOnExit();policy.toFile().deleteOnExit();
        var mapper=new ObjectMapper();
        Files.writeString(trusted,mapper.writeValueAsString(Map.of("test-key",publicKey.toString(),"old-key",oldPublic.toString())));
        String hash=new BCryptPasswordEncoder().encode("test-only-service-secret");
        var policies=Map.of("ttlSeconds",120,"services",List.of(
            Map.of("id","knode-content","credentialHash",hash,"audiences",Map.of("knode-user",List.of("users:public:read","auth:keys:read"))),
            Map.of("id","knode-community","credentialHash",hash,"audiences",Map.of("knode-user",List.of("users:state:read")))));
        Files.writeString(policy,mapper.writeValueAsString(policies));
        registry.add("KNODE_AUTH_TRUSTED_PUBLIC_KEYS_FILE",() -> trusted.toString());
        registry.add("KNODE_AUTH_SERVICE_POLICY_FILE",() -> policy.toString());
        registry.add("spring.datasource.url",() -> System.getProperty("knode.test.mysql.url","jdbc:h2:mem:knode_user_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username",() -> System.getProperty("knode.test.mysql.user","sa"));registry.add("spring.datasource.password",() -> "");
        registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "600000");
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");
        registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",() -> privateKey.toString());
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",() -> publicKey.toString());
    }
    @BeforeAll void schema() { new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource); }
    @BeforeEach void setup() {
        for (String table:new String[]{"auth_revocation_outbox","auth_revocation_intent","auth_login_session","sys_role_menu","sys_user_role","yan_menu","sys_role","sys_user"})
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
        assertEquals(503,call(HttpMethod.GET,"/api/blog/user/userInfo",null,c).getStatusCode().value());
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",c).getStatusCode().value());
        failWrite.set(false);
        assertEquals(200,call(HttpMethod.POST,"/api/blog/auth/logout","{}",c).getStatusCode().value());
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

    void permission(long menuId,String permission) {
        jdbc.update("INSERT INTO yan_menu(id,parent_id,menu_name,order_num,menu_type,status,visible,del_flag,perms) VALUES(?,100,?,9,'F','0','0','0',?)",menuId,"permission-"+menuId,permission);
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(10,?)",menuId);
    }
    @Test void adminUserCrudProtectsCredentialsAndRevokesSessions() throws Exception {
        permission(900,"*:*:*");
        String admin=login("admin","admin");
        assertTrue(data(call(HttpMethod.GET,"/api/admin/ops/revocations/metrics",null,admin)).path("data").path("retryAttempts").isTextual());
        var list=call(HttpMethod.GET,"/api/admin/system/user/list?pageNum=1&pageSize=10",null,admin);
        assertEquals(200,data(list).path("code").asInt());assertTrue(data(list).path("data").path("total").isTextual());
        assertFalse(list.getBody().contains("password"));assertTrue(data(list).path("data").path("rows").get(0).path("id").isTextual());
        var detail=call(HttpMethod.GET,"/api/admin/system/user/43",null,admin);
        assertFalse(detail.getBody().contains("password"));assertTrue(data(detail).path("data").has("roleIds"));
        var add=call(HttpMethod.POST,"/api/admin/system/user","{\"id\":1,\"type\":\"0\",\"userName\":\"newadmin\",\"nickName\":\"New Admin\",\"password\":\"test-password\",\"status\":\"0\",\"email\":\"newadmin@example.invalid\",\"sex\":\"2\",\"roleIds\":[10],\"createBy\":1}",admin);
        assertEquals(200,data(add).path("code").asInt());
        long id=jdbc.queryForObject("SELECT id FROM sys_user WHERE user_name='newadmin'",Long.class);
        assertNotEquals(1,id);assertEquals("1",jdbc.queryForObject("SELECT type FROM sys_user WHERE id=?",String.class,id));
        String blog=login("newadmin","blog"), adminSession=login("newadmin","admin");
        var reset=call(HttpMethod.PUT,"/api/admin/system/user/resetPwd","{\"userId\":"+id+",\"password\":\"changed-password\"}",admin);
        assertEquals(200,data(reset).path("code").asInt());
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,blog).getStatusCode().value());
        assertEquals(401,call(HttpMethod.GET,"/api/admin/auth/me",null,adminSession).getStatusCode().value());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE user_id=? AND state='PENDING'",Integer.class,id));
        var update=call(HttpMethod.PUT,"/api/admin/system/user","{\"id\":"+id+",\"userName\":\"hijack\",\"password\":\"sneak-password\",\"type\":\"0\",\"nickName\":\"Changed\",\"status\":\"0\",\"email\":\"newadmin@example.invalid\",\"sex\":\"1\",\"roleIds\":[10]}",admin);
        assertEquals(200,data(update).path("code").asInt());
        assertEquals("newadmin",jdbc.queryForObject("SELECT user_name FROM sys_user WHERE id=?",String.class,id));
        assertEquals("1",jdbc.queryForObject("SELECT type FROM sys_user WHERE id=?",String.class,id));
        assertTrue(encoder.matches("changed-password",jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?",String.class,id)));
        assertEquals(200,data(call(HttpMethod.PUT,"/api/admin/system/user/changeStatus","{\"userId\":"+id+",\"status\":\"1\"}",admin)).path("code").asInt());
        assertEquals(200,data(call(HttpMethod.DELETE,"/api/admin/system/user/"+id,null,admin)).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?",Integer.class,id));
    }
    @Test void adminRoleAndMenuCrudAndTreesUseRealPermissions() throws Exception {
        permission(900,"*:*:*");String admin=login("admin","admin");
        var menu=call(HttpMethod.POST,"/api/admin/system/menu","{\"id\":1,\"menuName\":\"Feature\",\"parentId\":100,\"orderNum\":4,\"menuType\":\"C\",\"visible\":\"0\",\"status\":\"0\",\"perms\":\"feature:read\"}",admin);
        assertEquals(200,data(menu).path("code").asInt());
        long menuId=jdbc.queryForObject("SELECT id FROM yan_menu WHERE menu_name='Feature'",Long.class);
        var role=call(HttpMethod.POST,"/api/admin/system/role","{\"roleName\":\"Feature Role\",\"roleKey\":\"feature\",\"roleSort\":5,\"status\":\"0\",\"menuIds\":["+menuId+"]}",admin);
        assertEquals(200,data(role).path("code").asInt());
        assertEquals(200,data(call(HttpMethod.GET,"/api/admin/system/menu/list",null,admin)).path("code").asInt());
        assertTrue(data(call(HttpMethod.GET,"/api/admin/system/menu/treeselect",null,admin)).path("data").isArray());
        assertTrue(data(call(HttpMethod.GET,"/api/admin/system/role/listAllRole",null,admin)).path("data").isArray());
        long roleId=jdbc.queryForObject("SELECT id FROM sys_role WHERE role_key='feature'",Long.class);
        assertTrue(data(call(HttpMethod.GET,"/api/admin/system/role/list?pageNum=1&pageSize=1",null,admin)).path("data").path("total").isTextual());
        assertEquals(Long.toString(menuId),data(call(HttpMethod.GET,"/api/admin/system/role/"+roleId,null,admin)).path("data").path("menuIds").get(0).asText());
        assertEquals(Long.toString(menuId),data(call(HttpMethod.GET,"/api/admin/system/menu/"+menuId,null,admin)).path("data").path("id").asText());
        var roleUpdate=call(HttpMethod.PUT,"/api/admin/system/role","{\"id\":"+roleId+",\"roleName\":\"Feature Updated\",\"roleKey\":\"feature\",\"roleSort\":5,\"status\":\"0\",\"menuIds\":["+menuId+"]}",admin);
        assertEquals(200,data(roleUpdate).path("code").asInt());
        var tree=data(call(HttpMethod.GET,"/api/admin/system/menu/roleMenuTreeselect/"+roleId,null,admin)).path("data");
        assertTrue(tree.has("menus"));assertEquals(Long.toString(menuId),tree.path("checkedKeys").get(0).asText());
        assertEquals(200,data(call(HttpMethod.PUT,"/api/admin/system/menu","{\"id\":"+menuId+",\"menuName\":\"Feature 2\",\"parentId\":100,\"orderNum\":4,\"menuType\":\"C\",\"visible\":\"0\",\"status\":\"0\",\"perms\":\"feature:read\"}",admin)).path("code").asInt());
        var cycle=call(HttpMethod.PUT,"/api/admin/system/menu","{\"id\":100,\"menuName\":\"Root\",\"parentId\":101,\"orderNum\":1,\"menuType\":\"M\",\"visible\":\"0\",\"status\":\"0\"}",admin);
        assertEquals(515,data(cycle).path("code").asInt());
        assertEquals(0L,jdbc.queryForObject("SELECT parent_id FROM yan_menu WHERE id=100",Long.class));
        assertEquals(200,data(call(HttpMethod.PUT,"/api/admin/system/role/changeStatus","{\"roleId\":"+roleId+",\"status\":\"1\"}",admin)).path("code").asInt());
        assertEquals(200,data(call(HttpMethod.DELETE,"/api/admin/system/role/"+roleId,null,admin)).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=?",Integer.class,roleId));
        assertEquals(200,data(call(HttpMethod.DELETE,"/api/admin/system/menu/"+menuId,null,admin)).path("code").asInt());
    }
    @Test void restrictedAdminCannotEscalateAndCriticalChangesRollBack() throws Exception {
        permission(901,"system:user:add");permission(902,"system:role:edit");permission(903,"system:role:query");
        jdbc.update("INSERT INTO sys_role(id,role_name,role_key,role_sort,status,del_flag) VALUES (20,'Privileged','privileged',2,'0','0')");
        jdbc.update("INSERT INTO yan_menu(id,parent_id,menu_name,order_num,menu_type,status,visible,del_flag,perms) VALUES (920,100,'Danger',9,'F','0','0','0','system:root:danger')");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(20,920)");
        String admin=login("admin","admin");
        assertEquals(403,call(HttpMethod.DELETE,"/api/admin/system/user/43",null,admin).getStatusCode().value());
        assertEquals(403,call(HttpMethod.GET,"/api/admin/ops/revocations/metrics",null,admin).getStatusCode().value());
        String escalate="{\"userName\":\"evil\",\"nickName\":\"Evil\",\"password\":\"example\",\"email\":\"evil@example.invalid\",\"status\":\"0\",\"sex\":\"2\",\"roleIds\":[20]}";
        assertEquals(403,call(HttpMethod.POST,"/api/admin/system/user",escalate,admin).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_name='evil'",Integer.class));
        String roleEdit="{\"id\":10,\"roleName\":\"Editors\",\"roleKey\":\"editor\",\"roleSort\":1,\"status\":\"0\",\"menuIds\":[920]}";
        assertEquals(403,call(HttpMethod.PUT,"/api/admin/system/role",roleEdit,admin).getStatusCode().value());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=10",Integer.class)>0);
        var disable=call(HttpMethod.PUT,"/api/admin/system/role/changeStatus","{\"id\":10,\"status\":\"1\"}",admin);
        assertEquals(516,data(disable).path("code").asInt());
        assertEquals("0",jdbc.queryForObject("SELECT status FROM sys_role WHERE id=10",String.class));
    }

    @Test void retryBatchContinuesAfterOneSidFailure() throws Exception {
        String first=login("alice","blog"), second=login("bob","blog");
        String sidA=tokens.authenticateAnyScope(first).sid(), sidB=tokens.authenticateAnyScope(second).sid();
        failWrite.set(true);
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",first).getStatusCode().value());
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",second).getStatusCode().value());
        jdbc.update("UPDATE auth_revocation_intent SET next_attempt_at=NULL");
        failWrite.set(false);
        doAnswer(inv -> {
            String sid=inv.getArgument(0);
            if (sid.equals(sidA)) throw new SessionStore.Unavailable("sid A unavailable");
            sessions.remove(sid);return null;
        }).when(writer).revoke(anyString());
        assertEquals(1,coordinator.recoverPending(10));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sidA));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sidB));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE sid=? AND state='PENDING'",Integer.class,sidB));
        doAnswer(inv -> {sessions.remove((String)inv.getArgument(0));return null;}).when(writer).revoke(anyString());
        jdbc.update("UPDATE auth_revocation_intent SET next_attempt_at=NULL,claim_token=?,claim_until=? WHERE sid=?",UUID.randomUUID().toString(),java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),sidA);
        coordinator.poll();
        assertEquals("REVOKED",jdbc.queryForObject("SELECT state FROM auth_revocation_intent WHERE sid=?",String.class,sidA));
        assertTrue(coordinator.failures()>0);assertTrue(coordinator.successes()>0);
    }
    @Test void concurrentLogoutKeepsOneIntentAndOneEvent() throws Exception {
        String token=login("alice","blog"),sid=tokens.authenticateAnyScope(token).sid();
        var bothEntered=new java.util.concurrent.CountDownLatch(2);
        doAnswer(inv -> {
            bothEntered.countDown();
            if (!bothEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent revoke did not reach barrier");
            sessions.remove((String)inv.getArgument(0));return null;
        }).when(writer).revoke(anyString());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> action=() -> {start.await();return call(HttpMethod.POST,"/api/blog/auth/logout","{}",token).getStatusCode().value();};
            var one=executor.submit(action);var two=executor.submit(action);start.countDown();
            int a=one.get(),b=two.get();
            assertEquals(200,a);assertEquals(200,b);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=?",Integer.class,sid));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE sid=?",Integer.class,sid));
        } finally {executor.shutdownNow();}
    }
    @Test void rolePermissionChangesTakeEffectWithoutSessionRefresh() throws Exception {
        permission(900,"*:*:*");String admin=login("admin","admin");
        assertEquals(200,call(HttpMethod.GET,"/api/admin/system/menu/list",null,admin).getStatusCode().value());
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=10 AND menu_id=900");
        assertEquals(403,call(HttpMethod.GET,"/api/admin/system/menu/list",null,admin).getStatusCode().value());
    }

    @Test void cannotChangeCoreAccountsOrUpgradeTypeByOrdinaryEdit() throws Exception {
        permission(900,"*:*:*");String admin=login("admin","admin");
        jdbc.update("INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(1,'root','Root',?,'root@example.invalid','1','0','2',0)",encoder.encode("example"));
        jdbc.update("INSERT INTO sys_role(id,role_name,role_key,role_sort,status,del_flag) VALUES(1,'Core','core',0,'0','0')");
        assertEquals(403,call(HttpMethod.PUT,"/api/admin/system/user/changeStatus","{\"userId\":1,\"status\":\"1\"}",admin).getStatusCode().value());
        assertEquals(403,call(HttpMethod.DELETE,"/api/admin/system/role/1",null,admin).getStatusCode().value());
        var update=call(HttpMethod.PUT,"/api/admin/system/user","{\"id\":43,\"userName\":\"bob\",\"type\":\"1\",\"nickName\":\"Bob\",\"status\":\"0\",\"email\":\"bob@example.invalid\",\"sex\":\"2\",\"roleIds\":[10]}",admin);
        assertEquals(200,data(update).path("code").asInt());
        assertEquals("0",jdbc.queryForObject("SELECT type FROM sys_user WHERE id=43",String.class));
        assertEquals(403,call(HttpMethod.POST,"/api/admin/auth/login","{\"userName\":\"bob\",\"password\":\"test-password\"}",null).getStatusCode().value());
    }

    @Test void failedRelationInsertRollsBackRoleAndAllItsOldRelations() throws Exception {
        permission(900,"*:*:*");String admin=login("admin","admin");
        jdbc.update("INSERT INTO yan_menu(id,parent_id,menu_name,order_num,menu_type,status,visible,del_flag,perms) VALUES(950,100,'Rejected',9,'C','0','0','0','test:rejected')");
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=10",Integer.class);
        jdbc.execute("ALTER TABLE sys_role_menu ADD CONSTRAINT check_test_menu CHECK (menu_id <> 950)");
        try {
            var response=call(HttpMethod.PUT,"/api/admin/system/role","{\"id\":10,\"roleName\":\"Must Roll Back\",\"roleKey\":\"editor\",\"roleSort\":1,\"status\":\"0\",\"menuIds\":[101,950]}",admin);
            assertEquals(515,data(response).path("code").asInt());
        } finally {jdbc.execute("ALTER TABLE sys_role_menu DROP CONSTRAINT check_test_menu");}
        assertEquals("Editors",jdbc.queryForObject("SELECT role_name FROM sys_role WHERE id=10",String.class));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=10",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=10 AND menu_id=102",Integer.class));
    }
    @Test void simultaneousAdministratorDisablesCannotRemoveEveryManager() throws Exception {
        permission(900,"*:*:*");
        jdbc.update("UPDATE sys_user SET type='1' WHERE id=43");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(43,10)");
        String first=login("admin","admin"),second=login("bob","admin");
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(() -> {start.await();return call(HttpMethod.PUT,"/api/admin/system/user/changeStatus","{\"id\":43,\"status\":\"1\"}",first);});
            var two=pool.submit(() -> {start.await();return call(HttpMethod.PUT,"/api/admin/system/user/changeStatus","{\"id\":44,\"status\":\"1\"}",second);});
            start.countDown();var a=one.get();var b=two.get();
            assertTrue(a.getStatusCode().value()==200 || b.getStatusCode().value()==200);
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE type='1' AND status='0' AND del_flag=0",Integer.class)>=1);
        } finally {pool.shutdownNow();}
    }

    @Test void readinessChecksWriteCapabilityAndDoesNotKeepProbeRows() throws Exception {
        assertEquals(200,call(HttpMethod.GET,"/health/ready",null,null).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent",Integer.class));
        doThrow(new SessionStore.Unavailable("write denied")).when(writer).probe();
        assertEquals(503,call(HttpMethod.GET,"/health/ready",null,null).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent",Integer.class));
    }
    @Test void reservedRoleKeysAndUnownedMenuPermissionsCannotEscalate() throws Exception {
        permission(901,"system:role:add");permission(902,"system:menu:add");String admin=login("admin","admin");
        String reserved="{\"roleName\":\"Forged\",\"roleKey\":\"admin\",\"roleSort\":1,\"status\":\"0\",\"menuIds\":[]}";
        assertEquals(403,call(HttpMethod.POST,"/api/admin/system/role",reserved,admin).getStatusCode().value());
        String newMenu="{\"menuName\":\"Forged\",\"parentId\":100,\"orderNum\":1,\"menuType\":\"F\",\"visible\":\"0\",\"status\":\"0\",\"perms\":\"system:secret:admin\"}";
        assertEquals(403,call(HttpMethod.POST,"/api/admin/system/menu",newMenu,admin).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM yan_menu WHERE menu_name='Forged'",Integer.class));
    }

    @Test void ambiguousPathCannotTurnBlogScopeIntoAdminScope() throws Exception {
        permission(900,"*:*:*");String blog=login("admin","blog");
        assertEquals(403,call(HttpMethod.GET,"/api/admin/system/user/list",null,blog).getStatusCode().value());
        assertNotEquals(200,call(HttpMethod.GET,"/api/admin;v=1/system/user/list",null,blog).getStatusCode().value());
    }

    @Test void reservedCoreRoleAndItsMembersAreProtectedRegardlessOfId() throws Exception {
        permission(900,"*:*:*");String admin=login("admin","admin");
        jdbc.update("INSERT INTO sys_role(id,role_name,role_key,role_sort,status,del_flag) VALUES(50,'Legacy Admin','admin',0,'0','0')");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(43,50)");
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(50,101)");
        assertEquals(403,call(HttpMethod.PUT,"/api/admin/system/user/changeStatus","{\"id\":43,\"status\":\"1\"}",admin).getStatusCode().value());
        assertEquals(403,call(HttpMethod.PUT,"/api/admin/system/role/changeStatus","{\"id\":50,\"status\":\"1\"}",admin).getStatusCode().value());
        assertEquals(403,call(HttpMethod.DELETE,"/api/admin/system/menu/101",null,admin).getStatusCode().value());
    }

    @Test void malformedIntentCannotBeReportedAsCompleted() throws Exception {
        String token=login("alice","blog"),sid=tokens.authenticateAnyScope(token).sid();
        jdbc.update("INSERT INTO auth_revocation_intent(sid,user_id,scope,event_id,state) VALUES(?,42,'blog',?,'BROKEN')",sid,UUID.randomUUID().toString());
        assertEquals(503,call(HttpMethod.POST,"/api/blog/auth/logout","{}",token).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_revocation_outbox WHERE sid=?",Integer.class,sid));
    }

    @Test void restrictedRoleManagerCannotWriteCollationEquivalentCoreKeys() throws Exception {
        permission(901,"system:role:add");permission(902,"system:role:edit");
        String token=login("admin","admin");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_key='admin'",Integer.class));
        for (String key:List.of("admin ","ádmin","ADMIN","super_admin ","ad-min","super:admin")) {
            var create=call(HttpMethod.POST,"/api/admin/system/role",json.writeValueAsString(Map.of("roleName","Attempt","roleKey",key,"roleSort",1,"status","0","menuIds",List.of())),token);
            assertNotEquals(200,data(create).path("code").asInt(),"create "+key);
            var update=call(HttpMethod.PUT,"/api/admin/system/role",json.writeValueAsString(Map.of("id",10,"roleName","Editors","roleKey",key,"roleSort",1,"status","0","menuIds",List.of(101,102,901,902))),token);
            assertNotEquals(200,data(update).path("code").asInt(),"update "+key);
        }
        assertEquals("editor",jdbc.queryForObject("SELECT role_key FROM sys_role WHERE id=10",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role",Integer.class));
        if (System.getProperty("knode.test.mysql.url")!=null) {
            assertEquals(1,jdbc.queryForObject("SELECT CASE WHEN _utf8mb4'ádmin' COLLATE utf8mb4_unicode_ci = _utf8mb4'admin' COLLATE utf8mb4_unicode_ci THEN 1 ELSE 0 END",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT CASE WHEN _utf8mb4'admin ' COLLATE utf8mb4_unicode_ci = _utf8mb4'admin' COLLATE utf8mb4_unicode_ci THEN 1 ELSE 0 END",Integer.class));
        }
    }

    ResponseEntity<String> internal(HttpMethod method,String path,String body,String authorization) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization!=null) headers.add("Authorization",authorization);
        return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
    }
    String basic(String name,String secret) {
        return "Basic "+Base64.getEncoder().encodeToString((name+":"+secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    String serviceToken(String service,String... permissions) throws Exception {
        String request=json.writeValueAsString(Map.of("audience","knode-user","permissions",List.of(permissions)));
        var response=internal(HttpMethod.POST,"/internal/v1/auth/service-tokens",request,basic(service,"test-only-service-secret"));
        assertEquals(200,response.getStatusCode().value(),response.getBody());
        assertFalse(data(response).has("data"));assertTrue(data(response).path("expiresIn").isInt());
        return data(response).path("accessToken").asText();
    }
    @Test void serviceIssuanceEnforcesCredentialIdentityGrantAndStrictRequests() throws Exception {
        String request="{\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\",\"users:state:read\"]}";
        var empty=internal(HttpMethod.POST,"/internal/v1/auth/service-tokens","",basic("knode-content","test-only-service-secret"));
        assertEquals(400,empty.getStatusCode().value());assertEquals("INVALID_ARGUMENT",data(empty).path("error").path("code").asText());
        var granted=internal(HttpMethod.POST,"/internal/v1/auth/service-tokens",request,basic("knode-content","test-only-service-secret"));
        assertEquals(200,granted.getStatusCode().value());assertEquals("Bearer",data(granted).path("tokenType").asText());
        assertEquals(120,data(granted).path("expiresIn").asInt());assertEquals(1,data(granted).path("permissions").size());
        assertEquals("users:public:read",data(granted).path("permissions").get(0).asText());
        var verifier=new com.knode.auth.ServiceTokenVerifier(signingKeys.publicKeys(),java.time.Clock.systemUTC());
        var principal=verifier.verify(data(granted).path("accessToken").asText(),"knode-user",Set.of("users:public:read"));
        assertEquals("knode-content",principal.service());
        assertEquals(401,internal(HttpMethod.POST,"/internal/v1/auth/service-tokens",request,basic("knode-content","bad-secret")).getStatusCode().value());
        assertEquals(403,internal(HttpMethod.POST,"/internal/v1/auth/service-tokens","{\"audience\":\"other-service\",\"permissions\":[\"users:public:read\"]}",basic("knode-content","test-only-service-secret")).getStatusCode().value());
        assertEquals(403,internal(HttpMethod.POST,"/internal/v1/auth/service-tokens","{\"audience\":\"knode-user\",\"permissions\":[\"users:state:read\"]}",basic("knode-content","test-only-service-secret")).getStatusCode().value());
        assertEquals(400,internal(HttpMethod.POST,"/internal/v1/auth/service-tokens","{\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\"],\"sub\":\"fake\"}",basic("knode-content","test-only-service-secret")).getStatusCode().value());
        assertEquals(400,internal(HttpMethod.POST,"/internal/v1/auth/service-tokens","{\"audience\":\"knode-user\",\"audience\":\"knode-user\",\"permissions\":[\"users:public:read\"]}",basic("knode-content","test-only-service-secret")).getStatusCode().value());
        var headers=new HttpHeaders();headers.add("Authorization",basic("knode-content","test-only-service-secret"));headers.add("Authorization","Bearer fake");headers.setContentType(MediaType.APPLICATION_JSON);
        var ambiguous=http.exchange("/internal/v1/auth/service-tokens",HttpMethod.POST,new HttpEntity<>(request,headers),String.class);
        assertEquals(401,ambiguous.getStatusCode().value());assertTrue(data(ambiguous).has("error"));assertFalse(data(ambiguous).has("code"));
    }
    @Test void batchesAreOrderedPrivateAndDistinguishMissingInvalidAndUnavailable() throws Exception {
        String publicToken=serviceToken("knode-content","users:public:read"),stateToken=serviceToken("knode-community","users:state:read");
        String input="{\"userIds\":[\"42\",\"999\",\"42\",\"45\",\"46\"]}";
        var response=internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",input,"Bearer "+publicToken);
        assertEquals(200,response.getStatusCode().value());var items=data(response).path("items");assertEquals(4,items.size());
        assertEquals("42",items.get(0).path("userId").asText());assertEquals("1",items.get(0).path("profileRevision").asText());
        assertEquals("MISSING",items.get(1).path("status").asText());assertEquals("FOUND",items.get(2).path("status").asText());
        assertEquals("MISSING",items.get(3).path("status").asText());
        assertTrue(items.get(0).path("updatedAt").asText().endsWith("Z"));
        assertFalse(response.getBody().contains("email"));assertFalse(response.getBody().contains("password"));assertFalse(response.getBody().contains("roles"));
        var validity=internal(HttpMethod.POST,"/internal/v1/users/validity:batch","{\"userIds\":[\"45\",\"46\",\"999\"],\"purpose\":\"FOLLOW_TARGET\"}","Bearer "+stateToken);
        assertEquals(200,validity.getStatusCode().value());var states=data(validity).path("items");
        assertTrue(states.get(0).path("exists").asBoolean());assertFalse(states.get(0).path("active").asBoolean());
        assertFalse(states.get(1).path("exists").asBoolean());assertFalse(states.get(2).path("exists").asBoolean());
        assertEquals(403,internal(HttpMethod.POST,"/internal/v1/users/validity:batch","{\"userIds\":[\"42\"],\"purpose\":\"FOLLOW_TARGET\"}","Bearer "+publicToken).getStatusCode().value());
        for(String invalid:List.of("{\"userIds\":[]}", "{\"userIds\":[42]}", "{\"userIds\":[\"9223372036854775808\"]}", "{\"userIds\":[\"42\"],\"extra\":true}"))
            assertEquals(400,internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",invalid,"Bearer "+publicToken).getStatusCode().value());
        String tooMany=json.writeValueAsString(Map.of("userIds",java.util.Collections.nCopies(101,"42")));
        assertEquals(400,internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",tooMany,"Bearer "+publicToken).getStatusCode().value());
        assertEquals(400,internal(HttpMethod.POST,"/internal/v1/users/validity:batch","{\"userIds\":[\"42\"],\"purpose\":\"ADMIN\"}","Bearer "+stateToken).getStatusCode().value());
        String user=login("alice","blog");
        assertEquals(401,internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",input,"Bearer "+user).getStatusCode().value());
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,publicToken).getStatusCode().value());
        jdbc.execute("ALTER TABLE sys_user RENAME TO sys_user_temp");
        try {var failed=internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",input,"Bearer "+publicToken);
             assertEquals(503,failed.getStatusCode().value());assertTrue(data(failed).has("error"));assertFalse(data(failed).has("items"));}
        finally {jdbc.execute("ALTER TABLE sys_user_temp RENAME TO sys_user");}
    }
    @Test void adminProfileRevisionAdvancesForCollationEquivalentNames() throws Exception {
        permission(900,"*:*:*");
        String admin=login("admin","admin"), existingSession=login("alice","blog");
        String machine=serviceToken("knode-content","users:public:read");
        String batch="{\"userIds\":[\"42\"]}";
        var initialResponse=internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",batch,"Bearer "+machine);
        assertEquals(200,initialResponse.getStatusCode().value());
        var previous=data(initialResponse).path("items").get(0);
        assertEquals("Alice",previous.path("nickName").asText());
        java.time.Instant previousTime=java.time.Instant.parse(previous.path("updatedAt").asText());
        for (String name:List.of("ALICE","ÁLICE")) {
            String update=json.writeValueAsString(Map.of("id",42,"nickName",name,"status","0",
                    "email","alice@example.invalid","sex","2","roleIds",List.of()));
            var response=call(HttpMethod.PUT,"/api/admin/system/user",update,admin);
            assertEquals(200,response.getStatusCode().value());
            assertEquals(200,data(response).path("code").asInt());
            var result=internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch",batch,"Bearer "+machine);
            assertEquals(200,result.getStatusCode().value());
            var current=data(result).path("items").get(0);
            assertEquals("FOUND",current.path("status").asText());
            assertNotEquals(previous.path("nickName").asText(),current.path("nickName").asText());
            assertEquals(name,current.path("nickName").asText());
            assertEquals(Long.parseLong(previous.path("profileRevision").asText())+1,
                    Long.parseLong(current.path("profileRevision").asText()),"collation-equivalent display change advances revision");
            var updatedAt=java.time.Instant.parse(current.path("updatedAt").asText());
            assertTrue(current.path("updatedAt").asText().endsWith("Z"));
            assertFalse(updatedAt.isBefore(previousTime));
            previous=current;previousTime=updatedAt;
        }
        assertEquals(401,call(HttpMethod.GET,"/api/blog/user/userInfo",null,existingSession).getStatusCode().value());
        if (System.getProperty("knode.test.mysql.url")!=null) {
            assertEquals(1,jdbc.queryForObject("SELECT CASE WHEN _utf8mb4'Alice' COLLATE utf8mb4_unicode_ci = _utf8mb4'ALICE' COLLATE utf8mb4_unicode_ci THEN 1 ELSE 0 END",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT CASE WHEN _utf8mb4'ALICE' COLLATE utf8mb4_unicode_ci = _utf8mb4'ÁLICE' COLLATE utf8mb4_unicode_ci THEN 1 ELSE 0 END",Integer.class));
        }
    }

    @Test void publicProfileRevisionPersistsAndKeyOverlapIsVerifiable() throws Exception {
        String machine=serviceToken("knode-content","users:public:read","auth:keys:read");
        String user=login("alice","blog");
        var keys=internal(HttpMethod.GET,"/internal/v1/auth/keys",null,"Bearer "+machine);
        assertEquals(2,data(keys).path("keys").size());assertFalse(keys.getBody().contains("\"d\":"));
        var before=data(internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch","{\"userIds\":[\"42\"]}","Bearer "+machine)).path("items").get(0);
        assertEquals(200,call(HttpMethod.PUT,"/api/blog/user/userInfo","{\"nickName\":\"Changed Alice\",\"avatar\":\"/changed.png\",\"sex\":\"1\"}",user).getStatusCode().value());
        var after=data(internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch","{\"userIds\":[\"42\"]}","Bearer "+machine)).path("items").get(0);
        assertEquals(Long.parseLong(before.path("profileRevision").asText())+1,Long.parseLong(after.path("profileRevision").asText()));
        assertEquals("Changed Alice",after.path("nickName").asText());
        var now=java.time.Instant.now();
        String old=io.jsonwebtoken.Jwts.builder().header().keyId("old-key").and().issuer("knode-user").subject("knode-content")
            .audience().add("knode-user").and().claim("token_use","service").claim("permissions",List.of("users:public:read"))
            .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(120)))
            .signWith((java.security.interfaces.RSAPrivateKey)oldMachineKey.getPrivate(),io.jsonwebtoken.Jwts.SIG.RS256).compact();
        assertEquals(200,internal(HttpMethod.POST,"/internal/v1/users/public-profiles:batch","{\"userIds\":[\"42\"]}","Bearer "+old).getStatusCode().value());
        var retired=new com.knode.auth.ServiceTokenVerifier(Map.of("test-key",signingKeys.publicKeys().get("test-key")),java.time.Clock.systemUTC());
        assertEquals(401,assertThrows(com.knode.auth.AuthFailure.class,() -> retired.verify(old,"knode-user",Set.of("users:public:read"))).status());
        assertThrows(IllegalArgumentException.class,() -> new SigningKeyRing("test-key",null,signingKeys.publicKeys()));
    }
}
