package com.knode.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Never discovers credentials, scans Redis, or changes ACLs. */
@EnabledIfSystemProperty(named="knode.it.real.enabled", matches="true")
class ExternalStackIT {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Process> processes = new ArrayList<>();
    private final Set<String> sessionKeys = new LinkedHashSet<>();
    private String environment, dbUrl, dbUser, dbPassword, redisHost, redisPort, redisDb, redisTls;
    private String readUser, readPassword, writeUser, writePassword, businessUser, businessPassword, businessPrefix;
    private Path userJar, gatewayJar, keyDir, privateFile, publicFile;
    private int userPort, gatewayPort;
    private boolean databaseInitialized;
    private LettuceConnectionFactory readFactory, writeFactory, businessFactory;
    private StringRedisTemplate reader, writer, business;

    private static String setting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing explicit test environment variable " + name);
        return value;
    }
    private void configure() throws IOException {
        environment=setting("KNODE_IT_AUTH_ENVIRONMENT"); dbUrl=setting("KNODE_IT_DB_URL");
        ExternalStackGuard.check(environment, dbUrl); // Must precede every network connection.
        dbUser=setting("KNODE_IT_DB_USERNAME"); dbPassword=setting("KNODE_IT_DB_PASSWORD");
        redisHost=setting("KNODE_IT_REDIS_HOST"); redisPort=setting("KNODE_IT_REDIS_PORT");
        redisDb=setting("KNODE_IT_REDIS_DATABASE"); redisTls=setting("KNODE_IT_REDIS_TLS");
        if (!redisTls.equals("true") && !redisTls.equals("false")) throw new IllegalArgumentException("Invalid test TLS flag");
        int port=Integer.parseInt(redisPort), db=Integer.parseInt(redisDb);
        if (port<1 || port>65535 || db<0) throw new IllegalArgumentException("Invalid test Redis configuration");
        readUser=setting("KNODE_IT_REDIS_READ_USERNAME");readPassword=setting("KNODE_IT_REDIS_READ_PASSWORD");
        writeUser=setting("KNODE_IT_REDIS_WRITE_USERNAME");writePassword=setting("KNODE_IT_REDIS_WRITE_PASSWORD");
        businessUser=setting("KNODE_IT_REDIS_BUSINESS_USERNAME");businessPassword=setting("KNODE_IT_REDIS_BUSINESS_PASSWORD");
        businessPrefix=setting("KNODE_IT_REDIS_BUSINESS_PREFIX");
        if (!businessPrefix.matches("knode:verify-[a-z0-9-]{1,25}:v1:business:[a-z0-9:-]*") || businessPrefix.startsWith(prefix()))
            throw new IllegalArgumentException("A distinct verify- business test prefix is required");
        String gatewayPath=System.getProperty("knode.it.gateway.jar");
        if (gatewayPath==null || gatewayPath.isBlank()) throw new IllegalArgumentException("Gateway JAR parameter required");
        gatewayJar=Path.of(gatewayPath).toAbsolutePath().normalize();
        userJar=Path.of(System.getProperty("knode.it.user.jar","target/knode-user-1.0-SNAPSHOT.jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(userJar) || !Files.isRegularFile(gatewayJar)) throw new IllegalArgumentException("Built JAR files required");
        userPort=port();gatewayPort=port();while (gatewayPort==userPort) gatewayPort=port();
    }
    private int port() throws IOException {try (var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {return socket.getLocalPort();}}
    private void initializeDatabase() throws Exception {
        var ds=new DriverManagerDataSource(dbUrl,dbUser,dbPassword);
        try (Connection c=ds.getConnection(); Statement s=c.createStatement();
             ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            if (!rs.next() || rs.getInt(1)!=0) throw new IllegalStateException("Test schema must be empty");
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(ds);databaseInitialized=true;
        String hash=new BCryptPasswordEncoder().encode("synthetic-test-password");
        try (Connection c=ds.getConnection(); PreparedStatement p=c.prepareStatement(
                "INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(42,'it-user','IT User',?,'it@example.invalid','0','0','2',0)")) {
            p.setString(1,hash);p.executeUpdate();
        }
    }
    private void makeKeys() throws Exception {
        keyDir=Files.createTempDirectory(Path.of("target"),"external-stack-keys-");
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        privateFile=keyDir.resolve("private.pem");publicFile=keyDir.resolve("public.pem");
        Files.writeString(privateFile,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(publicFile,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
    }
    private LettuceConnectionFactory connection(String user,String password) {
        var cfg=new RedisStandaloneConfiguration(redisHost,Integer.parseInt(redisPort));
        cfg.setUsername(user);cfg.setPassword(RedisPassword.of(password));cfg.setDatabase(Integer.parseInt(redisDb));
        var builder=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3));
        if ("true".equals(redisTls)) builder.useSsl();
        var f=new LettuceConnectionFactory(cfg,builder.build());f.afterPropertiesSet();f.start();return f;
    }
    private void connectTestAccounts() {
        readFactory=connection(readUser,readPassword);writeFactory=connection(writeUser,writePassword);
        businessFactory=connection(businessUser,businessPassword);
        reader=new StringRedisTemplate(readFactory);writer=new StringRedisTemplate(writeFactory);business=new StringRedisTemplate(businessFactory);
    }
    private Process start(boolean gateway,boolean badRead,boolean badWrite) throws Exception {
        var java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
        List<String> cmd=new ArrayList<>(List.of(java,"-jar",(gateway?gatewayJar:userJar).toString(),"--server.address=127.0.0.1"));
        if (gateway) cmd.add("--spring.cloud.discovery.client.simple.instances.knode-user[0].uri=http://127.0.0.1:"+userPort);
        ProcessBuilder b=new ProcessBuilder(cmd);Map<String,String> e=b.environment();
        e.keySet().removeIf(k -> k.startsWith("KNODE_") || k.startsWith("SPRING_") || k.startsWith("NACOS_")
                || Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","SERVER_PORT","USER_PORT","GATEWAY_PORT").contains(k));
        if (gateway) {
            e.put("SERVER_PORT",Integer.toString(gatewayPort));e.put("GATEWAY_PORT",Integer.toString(gatewayPort));
            e.put("KNODE_GATEWAY_AUTH_CONFIGURED","true");e.put("KNODE_GATEWAY_AUTH_PUBLIC_KEYS","it-key="+publicFile.toAbsolutePath());
            e.put("KNODE_GATEWAY_AUTH_ENVIRONMENT",environment);e.put("KNODE_GATEWAY_AUTH_REDIS_HOST",redisHost);
            e.put("KNODE_GATEWAY_AUTH_REDIS_PORT",redisPort);e.put("KNODE_GATEWAY_AUTH_REDIS_DATABASE",redisDb);
            e.put("KNODE_GATEWAY_AUTH_REDIS_USERNAME",readUser);e.put("KNODE_GATEWAY_AUTH_REDIS_PASSWORD",badRead?UUID.randomUUID().toString():readPassword);
            e.put("KNODE_GATEWAY_AUTH_REDIS_TLS",redisTls);e.put("NACOS_DISCOVERY_ENABLED","false");e.put("NACOS_CONFIG_ENABLED","false");e.put("KNODE_GOV_NACOS_ENABLED","false");
        } else {
            e.put("USER_PORT",Integer.toString(userPort));e.put("KNODE_USER_DB_URL",dbUrl);e.put("KNODE_USER_DB_USERNAME",dbUser);e.put("KNODE_USER_DB_PASSWORD",dbPassword);
            e.put("KNODE_AUTH_ENVIRONMENT",environment);e.put("KNODE_AUTH_SIGNING_KID","it-key");
            e.put("KNODE_AUTH_SIGNING_PRIVATE_FILE",privateFile.toAbsolutePath().toString());e.put("KNODE_AUTH_SIGNING_PUBLIC_FILE",publicFile.toAbsolutePath().toString());
            e.remove("KNODE_AUTH_SIGNING_PRIVATE_ENV");e.remove("KNODE_AUTH_TRUSTED_PUBLIC_KEYS_FILE");e.remove("KNODE_AUTH_SERVICE_POLICY_FILE");e.remove("KNODE_AUTH_SERVICE_POLICY_ENV");
            for (String prefix:List.of("KNODE_AUTH_REDIS_READ_","KNODE_AUTH_REDIS_WRITE_")) {
                boolean read=prefix.contains("_READ_");e.put(prefix+"HOST",redisHost);e.put(prefix+"PORT",redisPort);
                e.put(prefix+"DATABASE",redisDb);e.put(prefix+"TLS",redisTls);e.put(prefix+"USERNAME",read?readUser:writeUser);
                e.put(prefix+"PASSWORD",(read?badRead:badWrite)?UUID.randomUUID().toString():(read?readPassword:writePassword));
            }
            e.put("KNODE_AUTH_REVOCATION_INITIAL_MS","1000");e.put("KNODE_AUTH_REVOCATION_POLL_MS","1000");
        }
        // The subprocess may log request or connection details: discard it, never persist secrets or tokens.
        b.redirectOutput(ProcessBuilder.Redirect.DISCARD);b.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=b.start();processes.add(p);awaitPort(p,gateway?gatewayPort:userPort);return p;
    }
    private void awaitPort(Process p,int port) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime()<end) {
            if (!p.isAlive()) fail("A test service exited during startup");
            try (Socket socket=new Socket()) {socket.connect(new InetSocketAddress("127.0.0.1",port),300);return;}
            catch (IOException ignored) {Thread.sleep(250);}
        }
        fail("A test service did not open its loopback port");
    }
    private void stop(Process p) throws Exception {
        if (p==null || !p.isAlive()) return;
        p.destroy();if (!p.waitFor(15,TimeUnit.SECONDS)) {p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
        if (p.isAlive()) throw new IllegalStateException("Failed to stop owned test process");
    }
    private HttpResponse<String> request(int port,String method,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token!=null) b.header("token",token);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login() throws Exception {
        var response=request(userPort,"POST","/api/blog/auth/login","{\"userName\":\"it-user\",\"password\":\"synthetic-test-password\"}",null);
        assertEquals(200,response.statusCode());JsonNode node=json.readTree(response.body());assertEquals(200,node.path("code").asInt());
        String token=node.path("data").path("token").asText();assertFalse(token.isBlank());
        JsonNode claims=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        String sid=claims.path("sid").asText();assertDoesNotThrow(()->UUID.fromString(sid));
        sessionKeys.add(prefix()+sid);return token;
    }
    private String prefix() {return "knode:"+environment+":v1:auth:session:";}
    private int status(int port,String token) throws Exception {return request(port,"GET","/api/blog/user/userInfo",null,token).statusCode();}
    private static void noperm(Runnable operation) {
        try {operation.run();fail("Expected Redis NOPERM");}
        catch (RuntimeException failure) {
            boolean found=false;for (Throwable t=failure;t!=null;t=t.getCause()) if (String.valueOf(t.getMessage()).contains("NOPERM")) found=true;
            assertTrue(found,"Expected Redis NOPERM response");
        }
    }
    private void aclAssertions() {
        String probe=prefix()+"it-"+UUID.randomUUID(), outside="knode:verify-outside-"+UUID.randomUUID()+":v1:auth:session:"+UUID.randomUUID(), businessProbe=businessPrefix+UUID.randomUUID();
        try {
            assertTrue(Boolean.TRUE.equals(writer.opsForValue().setIfAbsent(probe,"fixture",Duration.ofSeconds(60))));
            assertEquals("fixture",reader.opsForValue().get(probe));
            noperm(()->reader.opsForValue().set(probe,"changed"));
            noperm(()->reader.delete(probe));
            try {noperm(()->writer.opsForValue().set(outside,"fixture",Duration.ofSeconds(60)));}
            finally {try {writer.delete(outside);} catch (RuntimeException ignored) {}}
            try {
                assertTrue(Boolean.TRUE.equals(business.opsForValue().setIfAbsent(businessProbe,"fixture",Duration.ofSeconds(60))));
                assertEquals("fixture",business.opsForValue().get(businessProbe));
            } finally {business.delete(businessProbe);}
            noperm(()->business.opsForValue().set(probe,"changed"));
            noperm(()->business.delete(probe));
            assertEquals("fixture",reader.opsForValue().get(probe));
        } finally {writer.delete(probe);}
    }
    private String intent(String token) throws Exception {
        var payload=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);PreparedStatement p=c.prepareStatement("SELECT state FROM auth_revocation_intent WHERE sid=?")) {
            p.setString(1,payload.path("sid").asText());try (ResultSet rs=p.executeQuery()) {return rs.next()?rs.getString(1):"ABSENT";}
        }
    }
    private void awaitRevoked(String token) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime()<end) {if ("REVOKED".equals(intent(token))) return;Thread.sleep(500);}
        fail("Pending revocation did not recover");
    }
    @Test void realUserGatewayAndExplicitRedisAcl() throws Exception {
        configure(); // All guards run before opening the dedicated DB or Redis.
        try {
            initializeDatabase();makeKeys();connectTestAccounts();aclAssertions();
            Process user=start(false,false,false),gateway=start(true,false,false);
            String a=login(),b=login();assertFalse(a.equals(b),"Sessions must issue distinct tokens");
            assertEquals(200,status(userPort,a));assertEquals(200,status(gatewayPort,a));
            assertEquals(403,request(gatewayPort,"GET","/api/admin/auth/me",null,a).statusCode());
            assertEquals(200,request(userPort,"POST","/api/blog/auth/logout",null,a).statusCode());
            assertEquals(401,status(userPort,a));assertEquals(401,status(gatewayPort,a));
            assertEquals(200,status(userPort,b));assertEquals(200,status(gatewayPort,b));
            String c=login();assertEquals(401,status(userPort,a));assertEquals(401,status(gatewayPort,a));assertEquals(200,status(gatewayPort,c));
            stop(gateway);gateway=start(true,true,false);assertEquals(503,status(gatewayPort,b));
            stop(gateway);gateway=start(true,false,false);
            stop(user);user=start(false,true,false);assertEquals(503,status(userPort,b));
            stop(user);user=start(false,false,true);
            assertEquals(503,request(userPort,"POST","/api/blog/auth/logout",null,c).statusCode());assertEquals("PENDING",intent(c));
            stop(user);user=start(false,false,false);awaitRevoked(c);
            assertEquals(401,status(userPort,c));assertEquals(401,status(gatewayPort,c));assertEquals(200,status(userPort,b));assertEquals(200,status(gatewayPort,b));
        } finally {
            int cleanupFailures=0;
            for (Process p:processes) try {stop(p);} catch (Exception ex) {cleanupFailures++;}
            if (databaseInitialized && writer!=null) {
                try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);PreparedStatement p=c.prepareStatement("SELECT sid FROM auth_login_session WHERE user_id=42");ResultSet rs=p.executeQuery()) {
                    while (rs.next()) {String sid=rs.getString(1);UUID.fromString(sid);sessionKeys.add(prefix()+sid);}
                } catch (Exception ex) {cleanupFailures++;}
                for (String key:sessionKeys) try {writer.delete(key);} catch (RuntimeException ex) {cleanupFailures++;}
            }
            try {if (readFactory!=null) readFactory.destroy();} catch (RuntimeException ex) {cleanupFailures++;}
            try {if (writeFactory!=null) writeFactory.destroy();} catch (RuntimeException ex) {cleanupFailures++;}
            try {if (businessFactory!=null) businessFactory.destroy();} catch (RuntimeException ex) {cleanupFailures++;}
            try {if (privateFile!=null) Files.deleteIfExists(privateFile);} catch (IOException ex) {cleanupFailures++;}
            try {if (publicFile!=null) Files.deleteIfExists(publicFile);} catch (IOException ex) {cleanupFailures++;}
            try {if (keyDir!=null) Files.deleteIfExists(keyDir);} catch (IOException ex) {cleanupFailures++;}
            if (cleanupFailures>0) throw new IllegalStateException("Owned test cleanup failed; count="+cleanupFailures);
        }
    }
}
