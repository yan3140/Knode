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
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Process> processes = new ArrayList<>();
    private final Set<String> sessionKeys = new LinkedHashSet<>();
    private final Set<String> explicitProbeKeys = new LinkedHashSet<>();
    private String environment, dbUrl, dbUser, dbPassword, redisHost, redisPort, redisDb, redisTls;
    private String readUser, readPassword, writeUser, writePassword, businessUser, businessPassword, businessPrefix;
    private Path userJar, gatewayJar, keyDir, privateFile, publicFile;
    private int userPort, gatewayPort,managementPort;
    private Integer gatewayManagementPort;
    private boolean databaseInitialized,eventsEnabled,loginInProgress,nacosEnabled;
    private ExternalNacosGuard.Inputs nacosInputs;
    private ExternalNacosMode nacos;
    private Path nacosCache;
    private String nacosStage;
    private int cleanupFailureCount;
    private int exporterFailurePort;
    private long userStartedNanos;
    private ServerSocket exporterSink;
    private final java.util.concurrent.atomic.AtomicInteger failedExports=new java.util.concurrent.atomic.AtomicInteger();
    private String processStarting;
    private String eventStream,eventProbePrefix,otlpEndpoint,jaegerEndpoint,lastLogoutIngressTraceparent;
    private ExternalTraceProbe traceProbe;
    private Path manifest;
    private final List<Map<String,String>> eventHandoffs=new ArrayList<>();
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
        if (new HashSet<>(List.of(readUser,writeUser,businessUser)).size()!=3) throw new IllegalArgumentException("Three distinct test Redis identities required");
        businessPrefix=setting("KNODE_IT_REDIS_BUSINESS_PREFIX");
        if (!businessPrefix.matches("knode:verify-[a-z0-9-]{1,25}:v1:business:[a-z0-9:-]*")
                || !businessPrefix.startsWith("knode:"+environment+":v1:business:") || businessPrefix.startsWith(prefix()))
            throw new IllegalArgumentException("A distinct verify- business test prefix is required");
        String gatewayPath=System.getProperty("knode.it.gateway.jar");
        if (gatewayPath==null || gatewayPath.isBlank()) throw new IllegalArgumentException("Gateway JAR parameter required");
        gatewayJar=Path.of(gatewayPath).toAbsolutePath().normalize();
        userJar=Path.of(System.getProperty("knode.it.user.jar","target/knode-user-1.0-SNAPSHOT.jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(userJar) || !Files.isRegularFile(gatewayJar)) throw new IllegalArgumentException("Built JAR files required");
        eventsEnabled=Boolean.getBoolean("knode.it.events.enabled");
        nacosEnabled=Boolean.getBoolean("knode.it.nacos.combined.enabled");
        if (nacosEnabled) {
            if (!eventsEnabled) throw new IllegalArgumentException("Nacos combined mode requires the explicit events mode");
            nacosInputs=ExternalNacosGuard.check(System.getenv());
        }
        if (eventsEnabled) {
            eventStream=setting("KNODE_IT_EVENTS_STREAM");eventProbePrefix=setting("KNODE_IT_EVENTS_PROBE_PREFIX");
            otlpEndpoint=setting("KNODE_IT_OTLP_HTTP");jaegerEndpoint=setting("KNODE_IT_JAEGER_QUERY_HTTP");
            ExternalEventGuard.check(environment,businessPrefix,eventStream,eventProbePrefix,otlpEndpoint);
            jaegerEndpoint=ExternalEventGuard.checkJaegerEndpoint(jaegerEndpoint);
            traceProbe=new ExternalTraceProbe(jaegerEndpoint);
        }
        // Socket allocation and all external calls occur only after every input guard.
        userPort=port();gatewayPort=port();while (gatewayPort==userPort) gatewayPort=port();
        managementPort=port();while (managementPort==userPort || managementPort==gatewayPort) managementPort=port();
        if (eventsEnabled) {
            gatewayManagementPort=port();
            while (gatewayManagementPort==userPort || gatewayManagementPort==gatewayPort || gatewayManagementPort==managementPort) gatewayManagementPort=port();
        }
        if (nacosEnabled) {
            exporterFailurePort=port();
            while (Set.of(userPort,gatewayPort,managementPort,gatewayManagementPort).contains(exporterFailurePort)) exporterFailurePort=port();
        }
        Path manifestDir=Files.createTempDirectory(Path.of("target"),"external-stack-manifest-");
        manifest=manifestDir.resolve("manifest.json");
        if (nacosEnabled) {
            nacosCache=Files.createTempDirectory(Path.of("target"),"external-nacos-cache-").toAbsolutePath().normalize();
            nacos=new ExternalNacosMode(nacosInputs,nacosCache,() -> {
                try {saveManifest();} catch (IOException e) {throw new java.io.UncheckedIOException(e);}
            });
        }
        saveManifest();
        System.out.println("External stack manifest: "+manifest.toAbsolutePath());
    }
    private void saveManifest() throws IOException {
        var body=new LinkedHashMap<String,Object>();
        body.put("environment",environment);body.put("dbUrl",dbUrl);body.put("redisHost",redisHost);body.put("redisPort",redisPort);
        body.put("userPort",userPort);body.put("gatewayPort",gatewayPort);body.put("managementPort",managementPort);
        body.put("gatewayManagementPort",gatewayManagementPort);
        body.put("eventsEnabled",eventsEnabled);body.put("stream",eventStream);body.put("probePrefix",eventProbePrefix);
        body.put("nacosEnabled",nacosEnabled);body.put("nacos",nacos==null?null:nacos.manifest());
        body.put("nacosStage",nacosStage);body.put("cleanupFailures",cleanupFailureCount);
        body.put("exporterFailurePort",nacosEnabled?exporterFailurePort:null);
        body.put("failedExporterConnections",failedExports.get());
        body.put("otlpEndpoint",otlpEndpoint);body.put("jaegerQueryOrigin",jaegerEndpoint);body.put("sessionKeys",List.copyOf(sessionKeys));
        body.put("explicitProbeKeys",List.copyOf(explicitProbeKeys));body.put("loginInProgress",loginInProgress);
        body.put("ownedProcesses",processes.stream().map(p -> Map.of("pid",p.pid(),"alive",p.isAlive())).toList());
        body.put("processStarting",processStarting);
        body.put("keyDirectory",keyDir==null?null:keyDir.toAbsolutePath().toString());
        body.put("keyFiles",privateFile==null?List.of():List.of(privateFile.toAbsolutePath().toString(),publicFile.toAbsolutePath().toString()));
        body.put("handoffs",List.copyOf(eventHandoffs));
        body.put("cleanup","Stop only listed owned processes; delete listed session keys. Keep the exact stream for the controller consumer; probes have TTL. No SCAN.");
        byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        Path staged=manifest.resolveSibling("manifest.pending");
        try (var channel=java.nio.channels.FileChannel.open(staged,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            var buffer=java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(staged,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
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
        privateFile=keyDir.resolve("private.pem");publicFile=keyDir.resolve("public.pem");saveManifest();
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
        List<String> cmd=new ArrayList<>();cmd.add(java);
        if (nacosEnabled) {
            String component=gateway?"gateway":"user";
            cmd.add("-DJM.SNAPSHOT.PATH="+nacosCache.resolve(component));
            cmd.add("-DJM.LOG.PATH="+nacosCache.resolve(component+"-logs"));
            cmd.add("-Dcsp.sentinel.log.dir="+nacosCache.resolve(component+"-sentinel"));
            cmd.add("-Dnacos.cache.data.init.snapshot=false");
        }
        cmd.addAll(List.of("-jar",(gateway?gatewayJar:userJar).toString(),"--server.address=127.0.0.1"));
        if (gateway) {
            if (!nacosEnabled) cmd.add("--spring.cloud.discovery.client.simple.instances.knode-user[0].uri=http://127.0.0.1:"+userPort);
            else cmd.add("--spring.cloud.loadbalancer.cache.enabled=false");
            if (eventsEnabled) {
                cmd.add("--management.server.address=127.0.0.1");
                cmd.add("--management.server.port="+gatewayManagementPort);
            }
        }
        if (nacosEnabled && !gateway) cmd.add("--spring.cloud.nacos.discovery.ip=127.0.0.1");
        ProcessBuilder b=new ProcessBuilder(cmd);Map<String,String> e=b.environment();
        e.keySet().removeIf(k -> k.startsWith("KNODE_") || k.startsWith("SPRING_") || k.startsWith("NACOS_") || k.startsWith("USER_") || k.startsWith("OTEL_") || k.startsWith("MANAGEMENT_") || k.startsWith("SERVER_")
                || Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","SERVER_PORT","USER_PORT","GATEWAY_PORT").contains(k));
        if (gateway) {
            e.put("SERVER_PORT",Integer.toString(gatewayPort));e.put("GATEWAY_PORT",Integer.toString(gatewayPort));
            e.put("KNODE_GATEWAY_AUTH_CONFIGURED","true");e.put("KNODE_GATEWAY_AUTH_PUBLIC_KEYS","it-key="+publicFile.toAbsolutePath());
            e.put("KNODE_GATEWAY_AUTH_ENVIRONMENT",environment);e.put("KNODE_GATEWAY_AUTH_REDIS_HOST",redisHost);
            e.put("KNODE_GATEWAY_AUTH_REDIS_PORT",redisPort);e.put("KNODE_GATEWAY_AUTH_REDIS_DATABASE",redisDb);
            e.put("KNODE_GATEWAY_AUTH_REDIS_USERNAME",readUser);e.put("KNODE_GATEWAY_AUTH_REDIS_PASSWORD",badRead?UUID.randomUUID().toString():readPassword);
            e.put("KNODE_GATEWAY_AUTH_REDIS_TLS",redisTls);e.put("NACOS_DISCOVERY_ENABLED","false");e.put("NACOS_CONFIG_ENABLED","false");e.put("KNODE_GOV_NACOS_ENABLED","false");
            if (nacosEnabled) {
                e.put("NACOS_DISCOVERY_ENABLED","true");e.put("NACOS_CONFIG_ENABLED","true");
                e.put("NACOS_SERVER_ADDR",nacosInputs.address());e.put("NACOS_NAMESPACE",nacosInputs.namespace());
                e.put("NACOS_DISCOVERY_GROUP",nacosInputs.discoveryGroup());e.put("NACOS_GROUP",nacosInputs.configGroup());
                e.put("NACOS_USERNAME",nacosInputs.username());e.put("NACOS_PASSWORD",nacosInputs.password());
                e.put("NACOS_CONFIG_IMPORT","nacos:knode-gateway.yaml?group="+nacosInputs.configGroup());
                e.put("KNODE_GOV_NACOS_ENABLED","true");e.put("KNODE_GOV_NACOS_SERVER_ADDR",nacosInputs.address());
                e.put("KNODE_GOV_NACOS_NAMESPACE",nacosInputs.namespace());e.put("KNODE_GOV_NACOS_GROUP",nacosInputs.governanceGroup());
                e.put("KNODE_GOV_NACOS_USERNAME",nacosInputs.username());e.put("KNODE_GOV_NACOS_PASSWORD",nacosInputs.password());
            }
            if (eventsEnabled) {
                e.put("KNODE_OBSERVABILITY_ENABLED","true");e.put("KNODE_ENVIRONMENT",environment);
                e.put("KNODE_SERVICE_VERSION","verify");e.put("KNODE_OTLP_TRACES_ENDPOINT",otlpEndpoint);
                e.put("KNODE_TRACE_SAMPLE_RATIO","1.0");
            }
        } else {
            e.put("USER_PORT",Integer.toString(userPort));e.put("USER_MANAGEMENT_PORT",Integer.toString(managementPort));e.put("KNODE_USER_DB_URL",dbUrl);e.put("KNODE_USER_DB_USERNAME",dbUser);e.put("KNODE_USER_DB_PASSWORD",dbPassword);
            e.put("KNODE_AUTH_ENVIRONMENT",environment);e.put("KNODE_AUTH_SIGNING_KID","it-key");
            e.put("KNODE_AUTH_SIGNING_PRIVATE_FILE",privateFile.toAbsolutePath().toString());e.put("KNODE_AUTH_SIGNING_PUBLIC_FILE",publicFile.toAbsolutePath().toString());
            e.remove("KNODE_AUTH_SIGNING_PRIVATE_ENV");e.remove("KNODE_AUTH_TRUSTED_PUBLIC_KEYS_FILE");e.remove("KNODE_AUTH_SERVICE_POLICY_FILE");e.remove("KNODE_AUTH_SERVICE_POLICY_ENV");
            for (String prefix:List.of("KNODE_AUTH_REDIS_READ_","KNODE_AUTH_REDIS_WRITE_")) {
                boolean read=prefix.contains("_READ_");e.put(prefix+"HOST",redisHost);e.put(prefix+"PORT",redisPort);
                e.put(prefix+"DATABASE",redisDb);e.put(prefix+"TLS",redisTls);e.put(prefix+"USERNAME",read?readUser:writeUser);
                e.put(prefix+"PASSWORD",(read?badRead:badWrite)?UUID.randomUUID().toString():(read?readPassword:writePassword));
            }
            e.put("KNODE_AUTH_REVOCATION_INITIAL_MS","1000");e.put("KNODE_AUTH_REVOCATION_POLL_MS","1000");
            if (nacosEnabled) {
                e.put("USER_NACOS_DISCOVERY_ENABLED","true");e.put("USER_NACOS_CONFIG_ENABLED","true");
                e.put("USER_NACOS_SERVER_ADDR",nacosInputs.address());e.put("USER_NACOS_NAMESPACE",nacosInputs.namespace());
                e.put("USER_NACOS_GROUP",nacosInputs.discoveryGroup());e.put("USER_NACOS_CONFIG_GROUP",nacosInputs.configGroup());
                e.put("USER_GOVERNANCE_ENABLED","true");e.put("USER_GOVERNANCE_GROUP",nacosInputs.governanceGroup());
                e.put("USER_NACOS_USERNAME",nacosInputs.username());e.put("USER_NACOS_PASSWORD",nacosInputs.password());
            }
            if (eventsEnabled) {
                e.put("KNODE_SESSION_EVENTS_ENABLED","true");e.put("KNODE_SESSION_EVENTS_STREAM_KEY",eventStream);
                e.put("KNODE_SESSION_EVENTS_PROBE_PREFIX",eventProbePrefix);
                e.put("KNODE_SESSION_EVENTS_REDIS_HOST",redisHost);e.put("KNODE_SESSION_EVENTS_REDIS_PORT",redisPort);
                e.put("KNODE_SESSION_EVENTS_REDIS_DATABASE",redisDb);e.put("KNODE_SESSION_EVENTS_REDIS_TLS",redisTls);
                e.put("KNODE_SESSION_EVENTS_REDIS_USERNAME",businessUser);e.put("KNODE_SESSION_EVENTS_REDIS_PASSWORD",businessPassword);
                e.put("KNODE_SESSION_EVENTS_INITIAL_MS",nacosEnabled?"180000":"1000");
                e.put("KNODE_SESSION_EVENTS_POLL_MS",nacosEnabled?"60000":"1000");
                e.put("USER_OBSERVABILITY_ENABLED","true");e.put("USER_OBSERVABILITY_ENVIRONMENT",environment);
                e.put("USER_OBSERVABILITY_VERSION","verify");
                e.put("USER_OTLP_ENDPOINT",nacosEnabled?"http://127.0.0.1:"+exporterFailurePort+"/v1/traces":otlpEndpoint);
                e.put("USER_TRACING_SAMPLE_RATE","1.0");
            }
        }
        // The subprocess may log request or connection details: discard it, never persist secrets or tokens.
        b.redirectOutput(ProcessBuilder.Redirect.DISCARD);b.redirectError(ProcessBuilder.Redirect.DISCARD);
        processStarting=gateway?"gateway":"user";saveManifest();
        Process p=b.start();if (nacosEnabled && !gateway) userStartedNanos=System.nanoTime();
        processes.add(p);processStarting=null;saveManifest();awaitPort(p,gateway?gatewayPort:userPort);
        if (gateway && eventsEnabled) awaitPort(p,gatewayManagementPort);
        return p;
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
        if (manifest!=null) saveManifest();
    }
    private HttpResponse<String> request(int port,String method,String path,String body,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token!=null) b.header("token",token);
        if (eventsEnabled && path.endsWith("/auth/logout")) {
            lastLogoutIngressTraceparent="00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";
            b.header("traceparent",lastLogoutIngressTraceparent);
        }
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login() throws Exception {return loginAt(userPort);}
    private String loginAt(int port) throws Exception {
        loginInProgress=true;saveManifest();
        var response=request(port,"POST","/api/blog/auth/login","{\"userName\":\"it-user\",\"password\":\"synthetic-test-password\"}",null);
        assertEquals(200,response.statusCode());JsonNode node=json.readTree(response.body());assertEquals(200,node.path("code").asInt());
        String token=node.path("data").path("token").asText();assertFalse(token.isBlank());
        JsonNode claims=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        String sid=claims.path("sid").asText();assertDoesNotThrow(()->UUID.fromString(sid));
        sessionKeys.add(prefix()+sid);loginInProgress=false;saveManifest();return token;
    }
    private String prefix() {return "knode:"+environment+":v1:auth:session:";}
    private int status(int port,String token) throws Exception {return request(port,"GET","/api/blog/user/userInfo",null,token).statusCode();}
    private boolean userInfoOk(int port,String token) throws Exception {
        var response=request(port,"GET","/api/blog/user/userInfo",null,token);
        if (response.statusCode()!=200) return false;
        JsonNode body=json.readTree(response.body());
        return body.path("code").asInt()==200 && "42".equals(body.path("data").path("id").asText())
                && "IT User".equals(body.path("data").path("nickName").asText());
    }
    private static void noperm(Runnable operation) {
        try {operation.run();fail("Expected Redis NOPERM");}
        catch (RuntimeException failure) {
            boolean found=false;for (Throwable t=failure;t!=null;t=t.getCause()) if (String.valueOf(t.getMessage()).contains("NOPERM")) found=true;
            assertTrue(found,"Expected Redis NOPERM response");
        }
    }
    private void aclAssertions() throws IOException {
        String probe=prefix()+"it-"+UUID.randomUUID(), outside="knode:verify-outside-"+UUID.randomUUID()+":v1:auth:session:"+UUID.randomUUID(), businessProbe=businessPrefix+UUID.randomUUID();
        explicitProbeKeys.addAll(List.of(probe,outside,businessProbe));saveManifest();
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
    private String traceFromIntent(String token) throws Exception {
        String sid=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("sid").asText();
        try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);PreparedStatement p=c.prepareStatement("SELECT traceparent FROM auth_revocation_intent WHERE sid=?")) {
            p.setString(1,sid);try (ResultSet rs=p.executeQuery()) {if (!rs.next()) throw new IllegalStateException("Missing persisted intent");return rs.getString(1);}
        }
    }
    private void awaitPublished(String token,String expectedTrace,String route) throws Exception {
        String sid=json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("sid").asText();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime()<end) {
            try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);PreparedStatement p=c.prepareStatement("SELECT state,event_json,event_id FROM auth_revocation_outbox WHERE sid=?")) {
                p.setString(1,sid);try (ResultSet rs=p.executeQuery()) {
                    if (rs.next() && "PUBLISHED".equals(rs.getString(1))) {
                        JsonNode event=json.readTree(rs.getString(2));String trace=event.path("traceContext").path("traceparent").asText();
                        var verifier=new com.knode.observability.EventTraceContext(io.opentelemetry.api.OpenTelemetry.noop());
                        assertEquals(trace,verifier.fromStored(trace,null).orElseThrow().traceparent());
                        assertEquals(Objects.requireNonNull(expectedTrace),trace);
                        assertEquals(2,event.path("schemaVersion").asInt());assertEquals(rs.getString(3),event.path("eventId").asText());
                        eventHandoffs.add(Map.of("eventId",rs.getString(3),"traceparent",trace,"route",route));saveManifest();return;
                    }
                }
            }
            Thread.sleep(500);
        }
        fail("Actual logout event was not published");
    }

    private void stage(String name) throws IOException {nacosStage=name;saveManifest();}
    private void startExporterFailureSink() throws IOException {
        stage("exporter-failure-sink-starting");
        exporterSink=new ServerSocket();exporterSink.bind(new InetSocketAddress("127.0.0.1",exporterFailurePort));
        Thread sink=new Thread(() -> {
            while (!exporterSink.isClosed()) {
                try (Socket ignored=exporterSink.accept()) {failedExports.incrementAndGet();}
                catch (IOException closed) {if (!exporterSink.isClosed()) break;}
            }
        },"g11-exporter-failure-sink");
        sink.setDaemon(true);sink.start();stage("exporter-failure-sink-ready");
    }
    private void awaitCondition(java.util.concurrent.Callable<Boolean> condition,int seconds,String detail) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime()<end) {
            try {if (condition.call()) return;}
            catch (IOException transientFailure) { /* Services may still be starting. */ }
            Thread.sleep(250);
        }
        fail(detail+"; see manifest stage "+nacosStage);
    }
    private int publishedCount() throws Exception {
        try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);
             Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM auth_revocation_outbox WHERE state='PUBLISHED'")) {
            if (!r.next()) throw new IllegalStateException("No outbox count");
            return r.getInt(1);
        }
    }
    private void nacosScenario(Process user,Process gateway) throws Exception {
        stage("discovery-and-readiness");
        awaitCondition(() -> user.isAlive() && gateway.isAlive() && nacos.registered(userPort),90,"User registration unavailable");
        nacos.assertNoWrongDiscovery(userPort);
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==200,90,"User readiness unavailable");
        awaitCondition(() -> request(gatewayManagementPort,"GET","/actuator/health/readiness",null,null).statusCode()==200,90,"Gateway readiness unavailable");
        String[] tokens=new String[4];
        tokens[3]=login();
        awaitCondition(() -> userInfoOk(gatewayPort,tokens[3]),90,"Gateway did not discover actual User");
        for (int i=0;i<3;i++) tokens[i]=loginAt(gatewayPort);
        assertTrue(userInfoOk(userPort,tokens[3]));assertTrue(userInfoOk(gatewayPort,tokens[3]));
        awaitCondition(() -> failedExports.get()>0,30,"Expected an attempted failed User trace export");
        assertEquals(200,request(userPort,"GET","/health/ready",null,null).statusCode(),"Exporter failure must not affect User readiness");
        stage("other-namespace-isolation");
        nacos.publishOtherInvalidRelay();
        for (int i=0;i<12;i++) {
            assertEquals(200,request(userPort,"GET","/health/ready",null,null).statusCode(),"Other namespace affected User readiness");
            Thread.sleep(500);
        }
        nacos.restoreOther();nacos.assertOtherUnchanged();
        stage("relay-config-update");
        nacos.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":0}");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==503,60,"Invalid relay configuration did not degrade readiness");
        nacos.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":2}");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==200,60,"Valid relay configuration did not recover readiness");
        nacos.assertOtherUnchanged();
        long elapsed=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-userStartedNanos);
        if (elapsed>125) throw new IllegalStateException("Insufficient time before first relay poll for a deterministic batch check");
        for (int i=0;i<3;i++) assertEquals(200,request(gatewayPort,"POST","/api/blog/auth/logout",null,tokens[i]).statusCode());
        assertEquals(0,publishedCount(),"Relay published before the controlled initial poll");
        nacos.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":0}");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==503,60,"Second invalid config did not degrade readiness");
        assertEquals(0,publishedCount(),"First relay poll occurred before invalid configuration was observed");
        stage("last-known-good-batch");
        awaitCondition(() -> publishedCount()>=2,190,"Relay did not publish a two-event batch");
        Thread.sleep(8000);
        assertEquals(2,publishedCount(),"Invalid update must retain the last valid batch size of two");
        nacos.publish(ExternalNacosMode.RELAY,"{\"sessionEventRelayBatchSize\":3}");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==200,60,"Relay configuration recovery failed");
        awaitCondition(() -> publishedCount()==3,80,"Remaining actual logout event was not published");
        stage("user-governance-missing");
        nacos.removeFlow();
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==503,60,"Missing governance rules did not degrade readiness");
        assertTrue(userInfoOk(userPort,tokens[3]),"Missing rule must retain the previous empty rule set");
        nacos.publish(ExternalNacosMode.FLOW,"[]");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==200,60,"Missing governance readiness did not recover");
        stage("user-governance-invalid");
        nacos.publish(ExternalNacosMode.FLOW,"{\"invalid\":true}");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==503,60,"Invalid governance rules did not degrade readiness");
        assertTrue(userInfoOk(userPort,tokens[3]),"Invalid rule must retain the previous empty rule set");
        nacos.publish(ExternalNacosMode.FLOW,"[]");
        awaitCondition(() -> request(userPort,"GET","/health/ready",null,null).statusCode()==200,60,"Governance readiness did not recover");
        stage("user-governance");
        nacos.publish(ExternalNacosMode.FLOW,ExternalNacosMode.RULE);
        awaitCondition(() -> status(userPort,tokens[3])==429 && status(gatewayPort,tokens[3])==429,60,"User flow rule did not block direct and Gateway requests");
        var blocked=request(userPort,"GET","/api/blog/user/userInfo",null,tokens[3]);
        assertEquals(429,blocked.statusCode());assertTrue(blocked.headers().firstValue("Retry-After").isEmpty(),"Unexpected login-window limit");
        nacos.publish(ExternalNacosMode.FLOW,"[]");
        awaitCondition(() -> userInfoOk(userPort,tokens[3]) && userInfoOk(gatewayPort,tokens[3]),60,"Flow rule clear did not recover requests");
        nacos.assertOtherUnchanged();stage("completed");
    }

    private void removeNacosCache() throws Exception {
        Path target=Path.of("target").toAbsolutePath().normalize();
        if (!nacosCache.getParent().equals(target) || !nacosCache.getFileName().toString().startsWith("external-nacos-cache-"))
            throw new IllegalStateException("Invalid owned Nacos cache path");
        IOException last=null;
        for (int attempt=0;attempt<20;attempt++) {
            try (var paths=Files.walk(nacosCache)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                return;
            } catch (IOException failure) {last=failure;Thread.sleep(250);}
        }
        throw new IOException("Owned Nacos cache cleanup failed",last);
    }

    @Test void realUserGatewayAndExplicitRedisAcl() throws Exception {
        configure(); // All guards run before opening the dedicated DB or Redis.
        try {
            if (nacosEnabled) {
                stage("nacos-preflight");nacos.connect();nacos.preflight();nacos.assertEmptyGroup();startExporterFailureSink();
            }
            initializeDatabase();makeKeys();connectTestAccounts();aclAssertions();
            Process user=start(false,false,false),gateway=start(true,false,false);
            if (nacosEnabled) {nacosScenario(user,gateway);return;}
            String a=login(),b=login();assertFalse(a.equals(b),"Sessions must issue distinct tokens");
            assertEquals(200,status(userPort,a));assertEquals(200,status(gatewayPort,a));
            assertEquals(403,request(gatewayPort,"GET","/api/admin/auth/me",null,a).statusCode());
            assertEquals(200,request(eventsEnabled?gatewayPort:userPort,"POST","/api/blog/auth/logout",null,a).statusCode());
            if (eventsEnabled) {
                String producerTrace=traceFromIntent(a);
                ExternalEventGuard.checkProducerTrace(producerTrace,lastLogoutIngressTraceparent);
                awaitPublished(a,producerTrace,"gateway");
                traceProbe.awaitProducer(producerTrace,true,Duration.ofSeconds(60));
            }
            assertEquals(401,status(userPort,a));assertEquals(401,status(gatewayPort,a));
            assertEquals(200,status(userPort,b));assertEquals(200,status(gatewayPort,b));
            String c=login();assertEquals(401,status(userPort,a));assertEquals(401,status(gatewayPort,a));assertEquals(200,status(gatewayPort,c));
            stop(gateway);gateway=start(true,true,false);assertEquals(503,status(gatewayPort,b));
            stop(gateway);gateway=start(true,false,false);
            stop(user);user=start(false,true,false);assertEquals(503,status(userPort,b));
            stop(user);user=start(false,false,true);
            assertEquals(503,request(userPort,"POST","/api/blog/auth/logout",null,c).statusCode());assertEquals("PENDING",intent(c));
            String originalTrace=eventsEnabled?traceFromIntent(c):null;
            if (eventsEnabled) {
                ExternalEventGuard.checkProducerTrace(originalTrace,lastLogoutIngressTraceparent);
                traceProbe.awaitProducer(originalTrace,false,Duration.ofSeconds(60));
            }
            stop(user);user=start(false,false,false);awaitRevoked(c);
            if (eventsEnabled) awaitPublished(c,originalTrace,"direct-recovery");
            assertEquals(401,status(userPort,c));assertEquals(401,status(gatewayPort,c));assertEquals(200,status(userPort,b));assertEquals(200,status(gatewayPort,b));
        } finally {
            int cleanupFailures=0;
            for (Process p:processes) try {stop(p);} catch (Exception ex) {cleanupFailures++;}
            if (nacos!=null) {
                try {nacos.restore();} catch (Exception ex) {cleanupFailures++;}
                try {nacos.close();} catch (Exception ex) {cleanupFailures++;}
            }
            try {if (exporterSink!=null) exporterSink.close();} catch (IOException ex) {cleanupFailures++;}
            if (nacosCache!=null) try {removeNacosCache();} catch (Exception ex) {cleanupFailures++;}
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
            cleanupFailureCount=cleanupFailures;
            if (manifest!=null) try {saveManifest();} catch (IOException ex) {cleanupFailures++;}
            if (cleanupFailures>0) throw new IllegalStateException("Owned test cleanup failed; count="+cleanupFailures);
        }
    }
}
