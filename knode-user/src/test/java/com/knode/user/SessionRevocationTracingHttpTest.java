package com.knode.user;

import com.fasterxml.jackson.databind.*;
import com.knode.auth.UserTokenVerifier;
import com.knode.observability.EventTraceContext;
import com.knode.user.auth.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "USER_OBSERVABILITY_ENABLED=true","USER_OBSERVABILITY_ENVIRONMENT=test","USER_GOVERNANCE_ENABLED=true","USER_GOVERNANCE_GROUP=VERIFY_G8",
        "USER_NACOS_SERVER_ADDR=127.0.0.1:1","USER_NACOS_NAMESPACE=verify-test",
        "USER_OBSERVABILITY_VERSION=test","USER_OTLP_ENDPOINT=http://127.0.0.1:1/v1/traces",
        "management.tracing.sampling.probability=1.0"})
@AutoConfigureObservability
@Import(SessionRevocationTracingHttpTest.ExporterConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRevocationTracingHttpTest {
    @TestConfiguration static class ExporterConfig {
        @Bean InMemorySpanExporter inMemorySpanExporter() {return InMemorySpanExporter.create();}
        @Bean(name="knodeGovernanceConfig",destroyMethod="") com.alibaba.nacos.api.config.ConfigService governanceConfig() throws Exception {
            var service=mock(com.alibaba.nacos.api.config.ConfigService.class);
            when(service.getConfigAndSignListener(anyString(),anyString(),anyLong(),any())).thenReturn("[]");
            when(service.getServerStatus()).thenReturn("UP");
            return service;
        }
    }
    static KeyPair pair;static int managementPort;
    @org.springframework.boot.test.web.server.LocalServerPort int servicePort;
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;@Autowired DataSource data;
    @Autowired ObjectMapper json;@Autowired TransactionTemplate tx;@Autowired RevocationCoordinator coordinator;
    @Autowired SessionEventRelay relay;@Autowired OpenTelemetry telemetry;@Autowired InMemorySpanExporter exporter;
    @Autowired ObjectProvider<EventTraceContext> traces;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired com.knode.governance.GovernanceRules governanceRules;
    @MockitoBean SessionEventTransport transport;@MockitoBean SessionStore.Reader reader;@MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    final Map<String,SessionStore.Session> sessions=new ConcurrentHashMap<>();
    final AtomicBoolean failWrite=new AtomicBoolean();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1"))) {managementPort=socket.getLocalPort();}
        registry.add("management.server.port",() -> managementPort);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);pair=generator.generateKeyPair();
        Path priv=Files.createTempFile("trace-test-private-", ".pem"),pub=Files.createTempFile("trace-test-public-", ".pem");
        priv.toFile().deleteOnExit();pub.toFile().deleteOnExit();
        Files.writeString(priv,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(pub,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",priv::toString);
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",pub::toString);registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "600000");registry.add("KNODE_SESSION_EVENTS_INITIAL_MS",() -> "600000");
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:user_trace_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username",() -> "sa");registry.add("spring.datasource.password",() -> "");
    }
    @BeforeAll void schema() {new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(data);}
    @BeforeEach void setup() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(
                org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()));
        for (String table:List.of("auth_revocation_replay_audit","auth_revocation_outbox","auth_revocation_intent","auth_login_session","sys_user")) db.update("DELETE FROM "+table);
        sessions.clear();failWrite.set(false);reset(reader,writer,transport);exporter.reset();
        when(reader.find(anyString())).thenAnswer(inv -> Optional.ofNullable(sessions.get(inv.getArgument(0))));
        doAnswer(inv -> {SessionStore.Session s=inv.getArgument(0);sessions.put(s.sid(),s);return null;}).when(writer).create(any());
        doAnswer(inv -> {if (failWrite.get()) throw new SessionStore.Unavailable("synthetic");sessions.remove((String)inv.getArgument(0));return null;}).when(writer).revoke(anyString());
        db.update("INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(42,'alice','Alice',?,'alice@example.invalid','0','0','2',0)",new BCryptPasswordEncoder(4).encode("synthetic-password"));
    }
    ResponseEntity<String> call(String path,String body,String token,String traceparent) {
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        if (token!=null) h.add("token",token);if (traceparent!=null) h.add("traceparent",traceparent);
        return http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,h),String.class);
    }
    @Test void realHttpEntryAndAuthorityCallsObeyGovernanceAndPreserveDenials() throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!org.springframework.boot.actuate.health.Status.UP.equals(governanceRules.health().getStatus()) && System.nanoTime()<until) Thread.sleep(20);
        assertEquals(org.springframework.boot.actuate.health.Status.UP,governanceRules.health().getStatus());
        var flow=new com.alibaba.csp.sentinel.slots.block.flow.FlowRule("HTTP POST /api/blog/auth/login");
        flow.setGrade(1);flow.setCount(0);
        com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(List.of(flow));
        try {assertEquals(429,call("/api/blog/auth/login","{}",null,null).getStatusCode().value());}
        finally {com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(List.of());}
        assertEquals(200,call("/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"synthetic-password\"}",null,null).getStatusCode().value());
        try (var authority=new LocalDelegationAuthorityFixture((RSAPublicKey)pair.getPublic(),Clock.systemUTC())) {
            String sid=UUID.randomUUID().toString();
            var resource=Map.of("knowledgeBaseId","42","clientRequestId","governance-test");
            authority.put("proof","knode-knowledge","KNOWLEDGE_CREATE","42",sid,"blog","knode-knowledge",resource,
                    Map.of("knode-agent",List.of("agent:runs:create")),null,null,"1",java.time.Instant.now().plusSeconds(120),java.time.Instant.now().plusSeconds(180));
            long now=java.time.Instant.now().getEpochSecond();
            String token=io.jsonwebtoken.Jwts.builder().header().keyId("test-key").and().issuer("knode-user").subject("knode-user")
                    .audience().add("knode-knowledge").and().claim("token_use","service").claim("permissions",List.of("auth:proofs:read"))
                    .id(UUID.randomUUID().toString()).issuedAt(new Date(now*1000)).expiration(new Date((now+120)*1000))
                    .signWith((java.security.interfaces.RSAPrivateKey)pair.getPrivate(),io.jsonwebtoken.Jwts.SIG.RS256).compact();
            var subject=new com.knode.user.internal.DelegationAuthorityClient.Subject("42",sid,"blog");
            var request=new com.knode.user.internal.DelegationAuthorityClient.Request("proof","KNOWLEDGE_CREATE","CREATE",subject,"knode-knowledge",null,null,resource);
            try (var client=new com.knode.user.internal.DelegationAuthorityClient(Map.of("knode-knowledge",java.net.URI.create(authority.origin())),
                    audience -> token,Clock.systemUTC(),java.time.Duration.ofSeconds(2),new com.knode.governance.GovernedCall(),telemetry)) {
                authority.mode.set("DENIED");
                assertEquals(403,assertThrows(com.knode.user.internal.InternalFailure.class,() -> client.resolve("knode-knowledge",request)).status());
                authority.mode.set("UNKNOWN_SCHEMA");
                assertEquals(503,assertThrows(com.knode.user.internal.InternalFailure.class,() -> client.resolve("knode-knowledge",request)).status());
                authority.mode.set("NORMAL");
                var blocked=new com.alibaba.csp.sentinel.slots.block.flow.FlowRule("feign:delegationAuthority#resolve");
                blocked.setGrade(1);blocked.setCount(0);com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(List.of(blocked));
                try {assertEquals(503,assertThrows(com.knode.user.internal.InternalFailure.class,() -> client.resolve("knode-knowledge",request)).status());}
                finally {com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(List.of());}
                var remote=io.opentelemetry.api.trace.SpanContext.createFromRemoteParent(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbb",io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.builder().put("vendor","opaque").build());
                var parentContext=io.opentelemetry.context.Context.root().with(io.opentelemetry.api.trace.Span.wrap(remote))
                        .with(io.opentelemetry.api.baggage.Baggage.builder().put("private","synthetic").build());
                try (var scope=parentContext.makeCurrent()) {
                    assertEquals(resource,client.resolve("knode-knowledge",request).resource());
                    assertTrue(authority.lastTraceparent.get().contains(remote.getTraceId()));
                    assertNull(authority.lastTracestate.get());assertNull(authority.lastBaggage.get());
                }
            }
        }
    }

    @Test void managementEndpointsAreBoundToTheirOwnLoopbackPort() throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        var service=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+servicePort+"/actuator/prometheus"))
                .header("Forwarded","for=127.0.0.1;host=127.0.0.1:"+managementPort)
                .header("X-Forwarded-Port",Integer.toString(managementPort)).GET().build();
        assertEquals(401,client.send(service,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var management=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+managementPort+"/actuator/prometheus")).GET().build();
        var response=client.send(management,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        assertTrue(response.body().contains("http_server_requests") || response.body().contains("jvm_"));
        var health=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+managementPort+"/actuator/health/liveness")).GET().build();
        assertEquals(200,client.send(health,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!org.springframework.boot.actuate.health.Status.UP.equals(governanceRules.health().getStatus()) && System.nanoTime()<until) Thread.sleep(20);
        var span=telemetry.getTracer("user-test").spanBuilder("exporter.unavailable").startSpan();span.end();
        if (telemetry instanceof io.opentelemetry.sdk.OpenTelemetrySdk sdk)
            sdk.getSdkTracerProvider().forceFlush().join(5,java.util.concurrent.TimeUnit.SECONDS);
        var ready=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+servicePort+"/health/ready")).GET().build();
        var readyResponse=client.send(ready,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,readyResponse.statusCode());assertEquals("UP",json.readTree(readyResponse.body()).path("governanceRules").asText());
    }

    @Test void originalHttpSpanSurvivesFailedRevokeRecoveryAndTransportRetry() throws Exception {
        var login=call("/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"synthetic-password\"}",null,null);
        assertEquals(200,login.getStatusCode().value());
        String token=json.readTree(login.getBody()).path("data").path("token").asText();
        String sid=new UserTokenVerifier(Map.of("test-key",(RSAPublicKey)pair.getPublic()),Clock.systemUTC()).verifyToken(token).sid();
        String upstream="00-11111111111111111111111111111111-2222222222222222-01";
        failWrite.set(true);
        assertEquals(503,call("/api/blog/auth/logout","{}",token,upstream).getStatusCode().value());
        var row=db.queryForMap("SELECT state,event_id,event_schema_version,traceparent FROM auth_revocation_intent WHERE sid=?",sid);
        assertEquals("PENDING",row.get("state"));assertEquals(2,((Number)row.get("event_schema_version")).intValue());
        String parent=(String)row.get("traceparent");assertNotNull(parent);assertTrue(parent.startsWith("00-11111111111111111111111111111111-"));
        assertNotEquals(upstream,parent);
        failWrite.set(false);db.update("UPDATE auth_revocation_intent SET next_attempt_at=NULL WHERE sid=?",sid);
        var recoverySpan=telemetry.getTracer("user-test").spanBuilder("unrelated.recovery").startSpan();
        try (var scope=recoverySpan.makeCurrent()) {
            var restarted=new RevocationCoordinator(db,tx,writer,Clock.systemUTC(),traces);
            assertEquals(1,restarted.recoverPending(10));
        } finally {recoverySpan.end();}
        String envelope=db.queryForObject("SELECT event_json FROM auth_revocation_outbox WHERE sid=?",String.class,sid);
        JsonNode event=json.readTree(envelope);
        assertEquals(2,event.path("schemaVersion").asInt());assertEquals(parent,event.path("traceContext").path("traceparent").asText());
        assertEquals(1,event.path("traceContext").size());
        assertFalse(envelope.contains(token));assertFalse(envelope.contains("synthetic-password"));
        when(transport.enabled()).thenReturn(true);
        var deliveries=new ArrayList<String>();
        doAnswer(inv -> {String e=inv.getArgument(0);deliveries.add(e);if (deliveries.size()==1) throw new IllegalStateException("synthetic");return null;}).when(transport).publish(anyString());
        assertEquals(0,relay.relay(10));db.update("UPDATE auth_revocation_outbox SET next_attempt_at=NULL WHERE sid=?",sid);
        assertEquals(1,relay.relay(10));assertEquals(List.of(envelope,envelope),deliveries);
        if (telemetry instanceof io.opentelemetry.sdk.OpenTelemetrySdk sdk)
            sdk.getSdkTracerProvider().forceFlush().join(5,java.util.concurrent.TimeUnit.SECONDS);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (exporter.getFinishedSpanItems().stream().noneMatch(s -> parent.substring(36,52).equals(s.getSpanId())) && System.nanoTime()<deadline)
            Thread.sleep(20);
        var original=exporter.getFinishedSpanItems().stream().filter(s -> parent.substring(36,52).equals(s.getSpanId())).findFirst();
        assertTrue(original.isPresent());assertEquals("11111111111111111111111111111111",original.get().getTraceId());
        assertEquals("2222222222222222",original.get().getParentSpanId());
        var freshLogin=call("/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"synthetic-password\"}",null,null);
        String freshToken=json.readTree(freshLogin.getBody()).path("data").path("token").asText();
        String privateTrace="33333333333333333333333333333333";
        var pathRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+servicePort+"/api/blog/user/987654321"))
                .header("token",freshToken).header("traceparent","00-"+privateTrace+"-4444444444444444-01").GET().build();
        java.net.http.HttpClient.newHttpClient().send(pathRequest,java.net.http.HttpResponse.BodyHandlers.discarding());
        if (telemetry instanceof io.opentelemetry.sdk.OpenTelemetrySdk sdk)
            sdk.getSdkTracerProvider().forceFlush().join(5,java.util.concurrent.TimeUnit.SECONDS);
        long privateDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (exporter.getFinishedSpanItems().stream().noneMatch(s -> privateTrace.equals(s.getTraceId())) && System.nanoTime()<privateDeadline) Thread.sleep(20);
        assertTrue(exporter.getFinishedSpanItems().stream().anyMatch(s -> privateTrace.equals(s.getTraceId())));
        for (var span:exporter.getFinishedSpanItems()) {
            // TestRestTemplate also emits client spans in this JVM; inspect User server spans
            // and User's own authority-client span, not the test harness client.
            if (span.getKind()!=io.opentelemetry.api.trace.SpanKind.SERVER && !span.getName().equals("user.delegation.proof.resolve")) continue;
            var leaked=new TreeSet<String>();
            span.getAttributes().forEach((key,value) -> {
                String text=String.valueOf(value);
                if (text.contains(token) || text.contains(sid) || text.contains("synthetic-password")
                        || text.contains("987654321") || text.contains(Integer.toString(servicePort))) leaked.add(key.getKey());
            });
            assertTrue(leaked.isEmpty(),"unsafe attribute keys: "+leaked);
        }
        for (var meter:meters.getMeters()) {
            String tags=meter.getId().getTags().toString();
            String tagKeys=meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList().toString();
            assertFalse(tags.contains(sid),tagKeys);assertFalse(tags.contains("987654321"),tagKeys);assertFalse(tags.contains(token),tagKeys);
            assertFalse(tags.contains(Integer.toString(servicePort)),tagKeys);
        }
    }
}
