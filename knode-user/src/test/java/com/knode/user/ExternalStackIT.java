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
    private final ExternalFrontendProbe frontendProbe=new ExternalFrontendProbe();
    private final List<Process> processes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private record OwnedChild(ProcessHandle handle,java.time.Instant started) {}
    private final Map<Long,OwnedChild> observedChildren=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> sessionKeys = new LinkedHashSet<>();
    private final Set<String> explicitProbeKeys = new LinkedHashSet<>();
    private String environment, dbUrl, dbUser, dbPassword, redisHost, redisPort, redisDb, redisTls;
    private String readUser, readPassword, writeUser, writePassword, businessUser, businessPassword, businessPrefix;
    private Path userJar, gatewayJar, keyDir, privateFile, publicFile;
    private int userPort, gatewayPort,managementPort;
    private Integer gatewayManagementPort;
    private boolean databaseInitialized,eventsEnabled,loginInProgress,nacosEnabled;
    private boolean browserEnabled;
    private ExternalBrowserGuard.Inputs browserInputs;
    private Path browserRuntime;
    private int blogPort,adminPort,browserDebugPort;
    private Map<String,Object> browserCleanup;
    private JsonNode browserEvidence;
    private String browserFailureStage,browserStage;
    private final List<String> browserStages=new ArrayList<>();
    private final Map<String,Map<String,Object>> browserProbes=new LinkedHashMap<>();
    private ExternalNacosGuard.Inputs nacosInputs;
    private ExternalNacosMode nacos;
    private Path nacosCache;
    private String nacosStage;
    private final ExternalFailureAudit failureAudit=new ExternalFailureAudit();
    private boolean cleanupFinished;
    private Map<String,Object> nacosCacheCleanup;
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
        String browserFlag=System.getProperty("knode.it.browser.enabled","false");
        if (!Set.of("true","false").contains(browserFlag)) throw new IllegalArgumentException("Invalid browser mode flag");
        browserEnabled=Boolean.parseBoolean(browserFlag);
        if (browserEnabled) browserInputs=ExternalBrowserGuard.check(System.getenv(),Path.of(".."),eventsEnabled,nacosEnabled);
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
        if (browserEnabled) {
            Set<Integer> used=new HashSet<>(List.of(userPort,gatewayPort,managementPort));
            blogPort=distinctPort(used);adminPort=distinctPort(used);browserDebugPort=distinctPort(used);
            browserRuntime=Files.createTempDirectory(Path.of("target"),"external-browser-").toAbsolutePath().normalize();
            browserCleanup=Map.of("ownedPath",browserRuntime.toString(),"state","in-use","postForkCleanupRequired",true);
        }
        Path manifestDir=Files.createTempDirectory(Path.of("target"),"external-stack-manifest-");
        manifest=manifestDir.resolve("manifest.json");
        if (nacosEnabled) {
            nacosCache=Files.createTempDirectory(Path.of("target"),"external-nacos-cache-").toAbsolutePath().normalize();
            nacosCacheCleanup=Map.of("ownedPath",nacosCache.toString(),"state","in-use","postForkCleanupRequired",true);
            nacos=new ExternalNacosMode(nacosInputs,nacosCache,() -> {
                try {saveManifest();} catch (IOException e) {throw new java.io.UncheckedIOException(e);}
            });
        }
        saveManifest();
        System.out.println("External stack manifest: "+manifest.toAbsolutePath());
    }
    private synchronized void saveManifest() throws IOException {
        var body=new LinkedHashMap<String,Object>();
        body.put("environment",environment);body.put("dbUrl",dbUrl);body.put("redisHost",redisHost);body.put("redisPort",redisPort);
        body.put("userPort",userPort);body.put("gatewayPort",gatewayPort);body.put("managementPort",managementPort);
        body.put("gatewayManagementPort",gatewayManagementPort);
        body.put("eventsEnabled",eventsEnabled);body.put("stream",eventStream);body.put("probePrefix",eventProbePrefix);
        body.put("browserEnabled",browserEnabled);
        body.put("browserPorts",browserEnabled?Map.of("blog",blogPort,"admin",adminPort,"debug",browserDebugPort):null);
        body.put("browserRuntime",browserRuntime==null?null:browserRuntime.toString());
        body.put("browserPaths",browserRuntime==null?null:Map.of(
                "blogDirectory",browserRuntime.resolve("blog-frontend").toString(),
                "adminDirectory",browserRuntime.resolve("admin-frontend").toString(),
                "browserDirectory",browserRuntime.resolve("isolated-edge").toString(),
                "browserProfile",browserRuntime.resolve("isolated-edge/profile").toString(),
                "driverDirectory",browserRuntime.resolve("browser-driver").toString()));
        body.put("browserFrontendRevision",browserInputs==null?null:browserInputs.revision());
        body.put("browserCleanup",browserCleanup);body.put("browserEvidence",browserEvidence);body.put("browserFailureStage",browserFailureStage);
        body.put("browserStage",browserStage);body.put("browserStages",List.copyOf(browserStages));
        body.put("browserProbes",new LinkedHashMap<>(browserProbes));
        body.put("nacosEnabled",nacosEnabled);body.put("nacos",nacos==null?null:nacos.manifest());
        body.put("nacosStage",nacosStage);body.put("cleanupFailures",failureAudit.cleanupCount());
        body.put("cleanupComplete",cleanupFinished && failureAudit.cleanupCount()==0 && (nacosCacheCleanup==null
                || Boolean.FALSE.equals(nacosCacheCleanup.get("postForkCleanupRequired")))
                && (browserCleanup==null || Boolean.FALSE.equals(browserCleanup.get("postForkCleanupRequired"))));
        body.put("testForkPid",ProcessHandle.current().pid());
        body.put("testForkParentPid",ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(null));
        body.put("primaryFailure",failureAudit.primary());body.put("cleanupIssues",failureAudit.cleanup());
        body.put("nacosCacheCleanup",nacosCacheCleanup);
        body.put("exporterFailurePort",nacosEnabled?exporterFailurePort:null);
        body.put("failedExporterConnections",failedExports.get());
        body.put("otlpEndpoint",otlpEndpoint);body.put("jaegerQueryOrigin",jaegerEndpoint);body.put("sessionKeys",List.copyOf(sessionKeys));
        body.put("explicitProbeKeys",List.copyOf(explicitProbeKeys));body.put("loginInProgress",loginInProgress);
        body.put("ownedProcesses",processes.stream().map(p -> Map.of("pid",p.pid(),"alive",p.isAlive(),
                "start",p.info().startInstant().map(Object::toString).orElse(""))).toList());
        if (browserEnabled) processes.stream().filter(Process::isAlive).flatMap(p -> p.toHandle().descendants()).forEach(h ->
                observedChildren.computeIfAbsent(h.pid(),ignored -> new OwnedChild(h,h.info().startInstant().orElse(null))));
        body.put("ownedDescendants",observedChildren.values().stream().map(child ->
                Map.of("pid",child.handle().pid(),"alive",child.handle().isAlive(),
                        "start",child.started()==null?"":child.started().toString())).toList());
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
    private int distinctPort(Set<Integer> used) throws IOException {
        int value;do {value=port();} while (!used.add(value));return value;
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
        if (browserEnabled) try (Connection c=ds.getConnection()) {ExternalBrowserFixture.seed(c,hash);}
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
        List<ProcessHandle> children=p.toHandle().descendants().toList();
        if (manifest!=null) saveManifest();
        for (ProcessHandle child:children) if (child.isAlive()) child.destroy();
        p.destroy();if (!p.waitFor(15,TimeUnit.SECONDS)) {p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}
        for (ProcessHandle child:children) if (child.isAlive()) {
            child.destroyForcibly();try {child.onExit().get(10,TimeUnit.SECONDS);} catch (java.util.concurrent.TimeoutException timeout) {
                throw new IllegalStateException("Failed to stop an owned child process");
            }
        }
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

    /** Launches only a named child, with a fresh home and no test credentials in its environment. */
    private Process browserProcess(String name,List<String> command,Path workdir,Map<String,String> additions,Path output) throws Exception {
        var b=new ProcessBuilder(command).directory(workdir.toFile());
        Map<String,String> env=b.environment();env.clear();
        for (String key:List.of("SystemRoot","WINDIR","ComSpec","PATH","PATHEXT")) {
            String value=System.getenv(key);if (value!=null) env.put(key,value);
        }
        Path processDirectory=browserRuntime.resolve(name);
        Path home=processDirectory.resolve("home"),temp=processDirectory.resolve("temp");
        Files.createDirectories(home);Files.createDirectories(temp);
        env.put("HOME",home.toString());env.put("USERPROFILE",home.toString());
        env.put("APPDATA",home.toString());env.put("LOCALAPPDATA",home.toString());
        env.put("TEMP",temp.toString());env.put("TMP",temp.toString());
        env.putAll(additions);
        b.redirectOutput(output==null?ProcessBuilder.Redirect.DISCARD:ProcessBuilder.Redirect.to(output.toFile()));
        b.redirectError(ProcessBuilder.Redirect.DISCARD);
        processStarting=name;saveManifest();
        Process result=b.start();processes.add(result);processStarting=null;saveManifest();return result;
    }
    private String gitOutput(String name,List<String> args) throws Exception {
        Path out=browserRuntime.resolve(name+".out");
        var command=new ArrayList<String>();command.add(browserInputs.git().toString());command.addAll(args);
        Process p=browserProcess(name,command,browserInputs.root(),Map.of("GIT_OPTIONAL_LOCKS","0"),out);
        if (!p.waitFor(30,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IllegalStateException("Frontend revision check failed");
        if (Files.size(out)>65536) throw new IllegalStateException("Frontend status output exceeded limit");
        String value=Files.readString(out,StandardCharsets.UTF_8);Files.delete(out);return value;
    }
    private void verifyFrontendRevision() throws Exception {
        String actual=gitOutput("frontend-head",List.of("rev-parse","HEAD")).trim();
        if (!actual.equals(browserInputs.revision())) throw new IllegalStateException("Frontend revision differs from the supplied integration revision");
        String status=gitOutput("frontend-status",List.of("status","--porcelain","--untracked-files=normal","--","frond/sg-blog-vue","frond/sg-vue-admin"));
        if (!status.isBlank()) throw new IllegalStateException("Frontend checkout contains uncommitted changes");
    }
    private void browserStage(String value) throws IOException {
        browserStage=value;browserStages.add(value);saveManifest();
    }
    private String auditStage() {return browserEnabled?browserStage:nacosStage;}
    private void awaitFrontend(String name,Process process,int port) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(180),lastSaved=0;
        int attempts=0;
        while (System.nanoTime()<end) {
            if (!process.isAlive()) {
                var previous=new LinkedHashMap<>(browserProbes.getOrDefault(name,Map.of()));
                previous.put("selectedPort",port);previous.put("processAlive",false);
                previous.putIfAbsent("lastExceptionType","ProcessExited");
                previous.put("selectedPortListening",frontendProbe.listening(port));
                browserProbes.put(name,previous);saveManifest();
                throw new IllegalStateException("A frontend exited during startup");
            }
            var observation=frontendProbe.observe(port);attempts++;
            var detail=new LinkedHashMap<String,Object>();
            detail.put("selectedPort",port);detail.put("attempts",attempts);
            detail.put("lastHttpStatus",observation.status());detail.put("lastExceptionType",observation.errorType());
            detail.put("selectedPortListening",observation.listening());detail.put("processAlive",true);
            browserProbes.put(name,detail);
            long now=System.nanoTime();
            if (observation.status()!=null && observation.status()==200) {saveManifest();return;}
            if (lastSaved==0 || now-lastSaved>=TimeUnit.SECONDS.toNanos(5)) {saveManifest();lastSaved=now;}
            Thread.sleep(500);
        }
        saveManifest();throw new IllegalStateException("A frontend did not become ready");
    }
    private void browserScenario() throws Exception {
        browserStage("frontends-starting");
        var common=new HashMap<String,String>();
        common.put("NODE_OPTIONS","--openssl-legacy-provider");common.put("BROWSER","none");
        common.put("KNODE_IT_BROWSER_MODE","true");common.put("KNODE_GATEWAY_TARGET","http://127.0.0.1:"+gatewayPort);
        var blogEnv=new HashMap<>(common);blogEnv.put("HOST","127.0.0.1");blogEnv.put("PORT",Integer.toString(blogPort));
        Path blogDir=browserInputs.root().resolve("frond/sg-blog-vue");
        browserStage("blog-frontend-starting");
        Process blog=browserProcess("blog-frontend",List.of(browserInputs.node().toString(),browserInputs.blogService().toString(),
                "--inline","--config","build/webpack.dev.conf.js"),blogDir,blogEnv,null);
        var adminEnv=new HashMap<>(common);adminEnv.put("VUE_APP_BASE_API","/dev-api/api/admin");
        Path adminDir=browserInputs.root().resolve("frond/sg-vue-admin");
        browserStage("admin-frontend-starting");
        Process admin=browserProcess("admin-frontend",List.of(browserInputs.node().toString(),browserInputs.adminService().toString(),
                "serve","--host","127.0.0.1","--port",Integer.toString(adminPort)),adminDir,adminEnv,null);
        browserStage("blog-frontend-wait");awaitFrontend("blog",blog,blogPort);browserStage("blog-frontend-ready");
        browserStage("admin-frontend-wait");awaitFrontend("admin",admin,adminPort);browserStage("admin-frontend-ready");
        browserStage("edge-starting");
        Path profile=browserRuntime.resolve("isolated-edge/profile");Files.createDirectories(profile);
        Process edge=browserProcess("isolated-edge",List.of(browserInputs.edge().toString(),"--headless=new","--disable-gpu",
                "--disable-background-networking","--disable-component-update","--disable-extensions","--no-first-run",
                "--no-default-browser-check","--no-proxy-server","--remote-allow-origins=*",
                "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1",
                "--remote-debugging-address=127.0.0.1","--remote-debugging-port="+browserDebugPort,
                "--user-data-dir="+profile,"--incognito","about:blank"),browserRuntime,Map.of(),null);
        browserStage("edge-wait");awaitPort(edge,browserDebugPort);browserStage("edge-ready");
        loginInProgress=true;browserStage("driver-starting");
        Path evidence=browserRuntime.resolve("evidence.json");
        Process driver=browserProcess("browser-driver",List.of(browserInputs.node().toString(),browserInputs.driver().toString(),
                Integer.toString(browserDebugPort),Integer.toString(blogPort),Integer.toString(adminPort)),browserRuntime,Map.of("KNODE_IT_BROWSER_CHILD","true"),evidence);
        browserStage("driver-running");
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(6);
        while (driver.isAlive() && System.nanoTime()<deadline) {driver.waitFor(1,TimeUnit.SECONDS);saveManifest();}
        if (driver.isAlive()) throw new IllegalStateException("Browser journey timed out");
        if (driver.exitValue()!=0) {
            if (Files.isRegularFile(evidence) && Files.size(evidence)<1024) {
                try {
                    JsonNode failure=json.readTree(evidence.toFile());String stage=failure==null?"":failure.path("stage").asText();
                    if (failure!=null && failure.size()==2 && failure.path("failed").asBoolean()
                            && Set.of("connect","blog-login","blog-update","blog-logout","admin-login",
                            "admin-logout","admin-relogin","admin-user-edit","admin-role-edit","admin-menu-edit","network-assertions").contains(stage))
                        browserFailureStage=stage;
                } catch (IOException ignored) { }
            }
            throw new IllegalStateException("Browser journey failed; see the sanitized manifest stage");
        }
        if (!Files.isRegularFile(evidence) || Files.size(evidence)>200000) throw new IllegalStateException("Invalid browser evidence size");
        browserStage("driver-complete");
        JsonNode result=json.readTree(evidence.toFile());validateBrowserEvidence(result);
        browserStage("database-verification");
        try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword)) {ExternalBrowserFixture.verify(c);}
        browserEvidence=result;browserStage("completed");
    }
    void validateBrowserEvidence(JsonNode result) {
        if (result==null || result.size()!=4 || result.path("version").asInt()!=1 || !result.path("checks").isObject()
                || result.path("checks").size()!=5 || !result.path("events").isArray() || result.path("events").size()>2000)
            throw new IllegalStateException("Invalid browser evidence structure");
        for (String name:List.of("me","checkedKeys","userRelation","roleRelation","rootParent"))
            if (!result.path("checks").path(name).isBoolean() || !result.path("checks").path(name).asBoolean())
                throw new IllegalStateException("Browser evidence check failed");
        JsonNode other=result.path("otherFailureStatuses");
        if (!other.isObject() || other.size()>500) throw new IllegalStateException("Invalid unrelated failure evidence");
        other.fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches("[45][0-9]{2}") || !entry.getValue().isInt() || entry.getValue().asInt()<1)
                throw new IllegalStateException("Invalid unrelated failure count");
        });
        Set<String> paths=Set.of("/api/blog/auth/login","/api/blog/auth/logout","/api/blog/user/userInfo",
                "/dev-api/api/admin/auth/login","/dev-api/api/admin/auth/logout","/dev-api/api/admin/auth/me",
                "/dev-api/api/admin/auth/routers","/dev-api/api/admin/system/user","/dev-api/api/admin/system/user/list",
                "/dev-api/api/admin/system/user/9007199254740993","/dev-api/api/admin/system/role",
                "/dev-api/api/admin/system/role/list","/dev-api/api/admin/system/role/listAllRole",
                "/dev-api/api/admin/system/role/9007199254740995","/dev-api/api/admin/system/menu",
                "/dev-api/api/admin/system/menu/list","/dev-api/api/admin/system/menu/treeselect",
                "/dev-api/api/admin/system/menu/9007199254740997",
                "/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995");
        Map<String,Integer> observed=new HashMap<>();
        for (JsonNode event:result.path("events")) {
            if (!event.isObject() || event.size()!=3 || !Set.of("GET","POST","PUT","DELETE").contains(event.path("method").asText())
                    || !paths.contains(event.path("path").asText())
                    || !event.path("status").isInt() || event.path("status").asInt()<100 || event.path("status").asInt()>599)
                throw new IllegalStateException("Invalid browser request evidence");
            if (event.path("status").asInt()==200) observed.merge(event.path("method").asText()+" "+event.path("path").asText(),1,Integer::sum);
        }
        Map<String,Integer> required=Map.ofEntries(
                Map.entry("POST /api/blog/auth/login",1),Map.entry("GET /api/blog/user/userInfo",2),
                Map.entry("PUT /api/blog/user/userInfo",1),Map.entry("POST /api/blog/auth/logout",1),
                Map.entry("POST /dev-api/api/admin/auth/login",2),Map.entry("POST /dev-api/api/admin/auth/logout",1),
                Map.entry("GET /dev-api/api/admin/auth/me",2),
                Map.entry("GET /dev-api/api/admin/auth/routers",2),
                Map.entry("GET /dev-api/api/admin/system/user/9007199254740993",1),Map.entry("PUT /dev-api/api/admin/system/user",1),
                Map.entry("GET /dev-api/api/admin/system/role/9007199254740995",1),Map.entry("PUT /dev-api/api/admin/system/role",1),
                Map.entry("GET /dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995",1),
                Map.entry("GET /dev-api/api/admin/system/menu/9007199254740997",1),Map.entry("PUT /dev-api/api/admin/system/menu",1));
        required.forEach((key,count) -> {
            if (observed.getOrDefault(key,0)<count) throw new IllegalStateException("Required browser request evidence is missing");
        });
    }

    @Test void realUserGatewayAndExplicitRedisAcl() throws Exception {
        configure(); // All guards run before opening the dedicated DB or Redis.
        AssertionError primaryFailure=null;
        try {
            if (nacosEnabled) {
                stage("nacos-preflight");nacos.connect();nacos.preflight();nacos.assertEmptyGroup();startExporterFailureSink();
            }
            if (browserEnabled) {browserStage("frontend-revision-check");verifyFrontendRevision();browserStage("database-and-redis-setup");}
            initializeDatabase();makeKeys();connectTestAccounts();aclAssertions();
            if (browserEnabled) browserStage("services-starting");
            Process user=start(false,false,false),gateway=start(true,false,false);
            if (browserEnabled) browserStage("services-ready");
            if (nacosEnabled) {nacosScenario(user,gateway);return;}
            if (browserEnabled) {browserScenario();return;}
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
        } catch (Throwable failure) {
            primaryFailure=failureAudit.capturePrimary(failure,auditStage());
            if (manifest!=null) try {saveManifest();} catch (Throwable ex) {failureAudit.cleanupFailure("primary-manifest",auditStage(),ex);}
            throw primaryFailure;
        } finally {
            var reverse=new ArrayList<>(processes);Collections.reverse(reverse);
            for (Process p:reverse) try {stop(p);} catch (Throwable ex) {failureAudit.cleanupFailure("stop-process",auditStage(),ex);}
            for (OwnedChild child:observedChildren.values()) try {
                ProcessHandle h=child.handle();
                if (h.isAlive()) {
                    if (child.started()==null || !child.started().equals(h.info().startInstant().orElse(null)))
                        throw new IllegalStateException("Cannot verify ownership of an observed child");
                    h.destroyForcibly();h.onExit().get(10,TimeUnit.SECONDS);
                }
            } catch (Throwable ex) {failureAudit.cleanupFailure("browser-child-stop",auditStage(),ex);}
            if (browserRuntime!=null) {
                try {browserCleanup=ExternalBrowserCleanup.attempt(Path.of("target"),browserRuntime);}
                catch (Throwable ex) {
                    browserCleanup=Map.of("ownedPath",browserRuntime.toString(),"state","validation-failed","postForkCleanupRequired",true);
                    failureAudit.cleanupFailure("browser-runtime-path",auditStage(),ex);
                }
            }
            if (nacos!=null) {
                try {nacos.restore();} catch (Throwable ex) {failureAudit.cleanupFailure("nacos-restore",auditStage(),ex);}
                try {nacos.close();} catch (Throwable ex) {failureAudit.cleanupFailure("nacos-close",auditStage(),ex);}
            }
            try {if (exporterSink!=null) exporterSink.close();} catch (Throwable ex) {failureAudit.cleanupFailure("exporter-sink",auditStage(),ex);}
            if (nacosCache!=null) {
                try {nacosCacheCleanup=ExternalNacosCacheCleanup.attempt(Path.of("target"),nacosCache);}
                catch (Throwable ex) {
                    nacosCacheCleanup=Map.of("ownedPath",nacosCache.toString(),"state","validation-failed","postForkCleanupRequired",true);
                    failureAudit.cleanupFailure("nacos-cache-path",auditStage(),ex);
                }
            }
            if (databaseInitialized && writer!=null) {
                try (Connection c=DriverManager.getConnection(dbUrl,dbUser,dbPassword);PreparedStatement p=c.prepareStatement("SELECT sid FROM auth_login_session WHERE user_id IN (42,44)");ResultSet rs=p.executeQuery()) {
                    while (rs.next()) {String sid=rs.getString(1);UUID.fromString(sid);sessionKeys.add(prefix()+sid);}
                    loginInProgress=false;
                } catch (Throwable ex) {failureAudit.cleanupFailure("session-enumeration",auditStage(),ex);}
                try {saveManifest();} catch (Throwable ex) {failureAudit.cleanupFailure("session-manifest",auditStage(),ex);}
                for (String key:sessionKeys) try {writer.delete(key);} catch (Throwable ex) {failureAudit.cleanupFailure("session-delete",auditStage(),ex);}
            }
            try {if (readFactory!=null) readFactory.destroy();} catch (Throwable ex) {failureAudit.cleanupFailure("redis-read-close",auditStage(),ex);}
            try {if (writeFactory!=null) writeFactory.destroy();} catch (Throwable ex) {failureAudit.cleanupFailure("redis-write-close",auditStage(),ex);}
            try {if (businessFactory!=null) businessFactory.destroy();} catch (Throwable ex) {failureAudit.cleanupFailure("redis-business-close",auditStage(),ex);}
            try {if (privateFile!=null) Files.deleteIfExists(privateFile);} catch (Throwable ex) {failureAudit.cleanupFailure("private-key-delete",auditStage(),ex);}
            try {if (publicFile!=null) Files.deleteIfExists(publicFile);} catch (Throwable ex) {failureAudit.cleanupFailure("public-key-delete",auditStage(),ex);}
            try {if (keyDir!=null) Files.deleteIfExists(keyDir);} catch (Throwable ex) {failureAudit.cleanupFailure("key-directory-delete",auditStage(),ex);}
            cleanupFinished=true;
            if (manifest!=null) try {saveManifest();} catch (Throwable ex) {failureAudit.cleanupFailure("cleanup-manifest",auditStage(),ex);}
            failureAudit.finish(primaryFailure);
        }
    }
}
