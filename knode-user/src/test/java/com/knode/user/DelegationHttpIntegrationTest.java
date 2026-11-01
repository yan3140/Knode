package com.knode.user;

import com.fasterxml.jackson.databind.*;
import com.knode.auth.*;
import com.knode.user.auth.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DelegationHttpIntegrationTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DelegationHttpIntegrationTest {
    static final MutableTestClock TIME=new MutableTestClock();
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock testClock() {return TIME;}
    }
    static final String SECRET="synthetic-machine-secret";
    static final List<String> HEAVY=java.util.stream.IntStream.range(0,100).mapToObj(i -> String.format("p%03d",i)+"a".repeat(120)).toList();
    static KeyPair keys;static LocalDelegationAuthorityFixture fixture;
    static final Map<String,List<String>> ENVELOPE=Map.of(
            "knode-agent",List.of("agent:runs:create","agent:runs:execute","agent:checkpoint:write"),
            "knode-knowledge",List.of("knowledge:rag:search"));
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;@Autowired DataSource dataSource;@Autowired ObjectMapper json;
    @MockitoBean SessionStore.Reader reader;@MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    final Map<String,SessionStore.Session> sessions=new ConcurrentHashMap<>();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        fixture=new LocalDelegationAuthorityFixture((RSAPublicKey)keys.getPublic(),TIME);
        Path privateFile=Files.createTempFile("g7-test-private-",".pem"),publicFile=Files.createTempFile("g7-test-public-",".pem");
        Path serviceFile=Files.createTempFile("g7-test-services-",".json"),policyFile=Files.createTempFile("g7-test-policy-",".json"),secretFile=Files.createTempFile("g7-test-secret-",".txt");
        for (Path p:List.of(privateFile,publicFile,serviceFile,policyFile,secretFile)) p.toFile().deleteOnExit();
        Files.writeString(privateFile,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(publicFile,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        Files.writeString(secretFile,SECRET);var m=new ObjectMapper();String hash=new BCryptPasswordEncoder(4).encode(SECRET);
        var services=List.of(
                Map.of("id","knode-user","credentialHash",hash,"audiences",Map.of("knode-knowledge",List.of("auth:proofs:read"),"knode-agent",List.of("auth:proofs:read"))),
                Map.of("id","knode-knowledge","credentialHash",hash,"audiences",Map.of("knode-user",List.of("auth:delegations:issue","auth:delegations:validate"),"knode-agent",List.of("agent:runs:create","agent:runs:execute"))),
                Map.of("id","knode-agent","credentialHash",hash,"audiences",Map.of("knode-user",List.of("auth:delegations:issue","auth:delegations:validate","auth:delegations:validate-checkpoint"),"knode-agent",List.of("agent:runs:execute","agent:checkpoint:write"),"knode-knowledge",List.of("knowledge:rag:search"))),
                Map.of("id","knode-other","credentialHash",hash,"audiences",Map.of("knode-user",List.of("auth:delegations:issue"),"knode-agent",List.of("agent:runs:create"))),
                Map.of("id","knode-heavy","credentialHash",hash,"audiences",Map.of("knode-agent",HEAVY)));
        Files.writeString(serviceFile,m.writeValueAsString(Map.of("ttlSeconds",300,"services",services)));
        var rules=List.of(
                Map.of("actor","knode-knowledge","audience","knode-agent","purpose","CREATE","permissions",List.of("agent:runs:create")),
                Map.of("actor","knode-knowledge","audience","knode-agent","purpose","CONTINUE","permissions",List.of("agent:runs:execute")),
                Map.of("actor","knode-agent","audience","knode-agent","purpose","EXECUTE","permissions",List.of("agent:runs:execute")),
                Map.of("actor","knode-agent","audience","knode-knowledge","purpose","EXCHANGE","permissions",List.of("knowledge:rag:search")),
                Map.of("actor","knode-agent","audience","knode-agent","purpose","SAFE_CHECKPOINT","permissions",List.of("agent:checkpoint:write")));
        Files.writeString(policyFile,m.writeValueAsString(Map.of("rules",rules)));
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",privateFile::toString);
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",publicFile::toString);registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_SERVICE_POLICY_FILE",serviceFile::toString);registry.add("KNODE_DELEGATION_POLICY_FILE",policyFile::toString);
        registry.add("KNODE_DELEGATION_OUTBOUND_SECRET_FILE",secretFile::toString);
        registry.add("KNODE_DELEGATION_KNOWLEDGE_ORIGIN",fixture::origin);registry.add("KNODE_DELEGATION_AGENT_ORIGIN",fixture::origin);
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "600000");registry.add("KNODE_SESSION_EVENTS_INITIAL_MS",() -> "600000");
        registry.add("spring.datasource.url",() -> System.getProperty("knode.test.mysql.url","jdbc:h2:mem:g7_user;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username",() -> System.getProperty("knode.test.mysql.user","sa"));registry.add("spring.datasource.password",() -> "");
    }
    @BeforeAll void schema(){new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);}
    @AfterAll void close(){fixture.close();}
    @BeforeEach void setup() {
        TIME.reset();
        for (String table:List.of("auth_delegation_grant_audit","auth_delegation_grant","auth_revocation_replay_audit","auth_revocation_outbox","auth_revocation_intent","auth_login_session","sys_user")) db.update("DELETE FROM "+table);
        fixture.clear();sessions.clear();reset(reader,writer);
        when(reader.find(anyString())).thenAnswer(inv -> Optional.ofNullable(sessions.get(inv.getArgument(0))));
        doAnswer(inv -> {var session=(SessionStore.Session)inv.getArgument(0);sessions.put(session.sid(),session);return null;}).when(writer).create(any());
        doAnswer(inv -> {sessions.remove((String)inv.getArgument(0));return null;}).when(writer).revoke(anyString());
        db.update("INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(42,'alice','Alice',?,'alice@example.invalid','0','0','2',0)",new BCryptPasswordEncoder(4).encode("synthetic-user-password"));
    }
    ResponseEntity<String> call(HttpMethod method,String path,Object body,String authorization) throws Exception {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization!=null) headers.add("Authorization",authorization);
        return http.exchange(path,method,new HttpEntity<>(body==null?null:json.writeValueAsString(body),headers),String.class);
    }
    ResponseEntity<String> raw(HttpMethod method,String path,String body,String token) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if (token!=null) headers.add("token",token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
    }
    JsonNode read(ResponseEntity<String> response) throws Exception {return json.readTree(response.getBody());}
    String user() throws Exception {
        var response=raw(HttpMethod.POST,"/api/blog/auth/login","{\"userName\":\"alice\",\"password\":\"synthetic-user-password\"}",null);
        assertEquals(200,response.getStatusCode().value());return read(response).path("data").path("token").asText();
    }
    String machine(String service,String audience,String... permissions) throws Exception {
        String basic="Basic "+Base64.getEncoder().encodeToString((service+":"+SECRET).getBytes(StandardCharsets.UTF_8));
        var response=call(HttpMethod.POST,"/internal/v1/auth/service-tokens",Map.of("audience",audience,"permissions",List.of(permissions)),basic);
        assertEquals(200,response.getStatusCode().value());return read(response).path("accessToken").asText();
    }
    String sid(String token) {return new UserTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME).verifyToken(token).sid();}
    void proof(String ref,String authority,String purpose,String actor,String sid,Map<String,String> resource,String parent,String root,String revision) throws Exception {
        fixture.put(ref,authority,("knode-knowledge".equals(authority)?"KNOWLEDGE_":"AGENT_")+purpose,"42",sid,"blog",actor,resource,ENVELOPE,parent,root,revision,TIME.instant().plusSeconds(500),TIME.instant().plusSeconds(600));
    }
    ResponseEntity<String> issue(String caller,Object body) throws Exception {return call(HttpMethod.POST,"/internal/v1/auth/delegations",body,"Bearer "+caller);}
    Map<String,Object> issueBody(String sourceName,String source,String purpose,String audience,String permission,Map<String,String> refs,Map<String,String> resource) {
        var body=new LinkedHashMap<String,Object>();body.put(sourceName,source);body.put("purpose",purpose);body.put("audience",audience);
        body.put("permissions",List.of(permission));body.put("proofRefs",refs);body.put("expectedResource",resource);return body;
    }
    String issueToken(ResponseEntity<String> response) throws Exception {
        assertEquals(200,response.getStatusCode().value());assertTrue(read(response).path("expiresIn").isInt());
        return read(response).path("accessToken").asText();
    }
    String grant(String token) {return new DelegationTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME).verifyNormal(token,"knode-agent").grantId();}
    Map<String,Object> validation(String actorToken,String delegationToken,String permission,Map<String,String> resource) {
        return Map.of("actorToken",actorToken,"delegationToken",delegationToken,"permission",permission,"resource",resource);
    }
    @Test void verifiedCreationExecutionExchangeAndCheckpointStayBoundToTheActualActors() throws Exception {
        String user=user(),sid=sid(user);
        String knowledgeIssue=machine("knode-knowledge","knode-user","auth:delegations:issue","auth:delegations:validate");
        String agentIssue=machine("knode-agent","knode-user","auth:delegations:issue","auth:delegations:validate","auth:delegations:validate-checkpoint");
        String knowledgeToAgent=machine("knode-knowledge","knode-agent","agent:runs:create","agent:runs:execute");
        String agentToAgent=machine("knode-agent","knode-agent","agent:runs:execute","agent:checkpoint:write");
        String agentToKnowledge=machine("knode-agent","knode-knowledge","knowledge:rag:search");
        var creation=Map.of("knowledgeBaseId","42","clientRequestId","request-1");
        proof("k-create","knode-knowledge","CREATE","knode-knowledge",sid,creation,null,null,"1");
        String rootToken=issueToken(issue(knowledgeIssue,issueBody("subjectToken",user,"CREATE","knode-agent","agent:runs:create",Map.of("knowledge","k-create"),creation)));
        String root=grant(rootToken);
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(knowledgeToAgent,rootToken,"agent:runs:create",creation),"Bearer "+agentIssue).getStatusCode().value());
        var run=new LinkedHashMap<>(creation);run.put("runId","run-1");run.put("controlEpoch","1");run.put("workerId","worker-1");
        fixture.registerRun("run-1",root,"1","worker-1");
        proof("k-exec","knode-knowledge","EXECUTE","knode-agent",sid,run,root,root,"2");
        proof("a-exec","knode-agent","EXECUTE","knode-agent",sid,run,root,root,"2");
        var refresh=issueBody("grantId",root,"EXECUTE","knode-agent","agent:runs:execute",Map.of("knowledge","k-exec","agent","a-exec"),run);
        String refreshed=issueToken(issue(agentIssue,refresh));
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToAgent,refreshed,"agent:runs:execute",run),"Bearer "+agentIssue).getStatusCode().value());
        var claims=io.jsonwebtoken.Jwts.parser().verifyWith((RSAPublicKey)keys.getPublic()).build().parseSignedClaims(rootToken).getPayload();
        String expired=io.jsonwebtoken.Jwts.builder().header().keyId("test-key").and().claims(claims)
                .expiration(Date.from(Instant.now().minusSeconds(1))).signWith((RSAPrivateKey)keys.getPrivate(),io.jsonwebtoken.Jwts.SIG.RS256).compact();
        var expiredBody=issueBody("parentToken",expired,"EXECUTE","knode-agent","agent:runs:execute",Map.of("knowledge","k-exec","agent","a-exec"),run);
        assertEquals(401,issue(agentIssue,expiredBody).getStatusCode().value());
        expiredBody.put("grantId",root);
        assertEquals(400,issue(agentIssue,expiredBody).getStatusCode().value());
        String execution=issueToken(issue(agentIssue,issueBody("parentToken",rootToken,"EXECUTE","knode-agent","agent:runs:execute",Map.of("knowledge","k-exec","agent","a-exec"),run)));
        String executionGrant=grant(execution);
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToAgent,execution,"agent:runs:execute",run),"Bearer "+agentIssue).getStatusCode().value());
        fixture.advanceRun("run-1","2","worker-2");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToAgent,execution,"agent:runs:execute",run),"Bearer "+agentIssue).getStatusCode().value());
        fixture.advanceRun("run-1","1","worker-1");
        proof("k-exchange","knode-knowledge","EXCHANGE","knode-agent",sid,run,executionGrant,root,"3");
        proof("a-exchange","knode-agent","EXCHANGE","knode-agent",sid,run,executionGrant,root,"3");
        String exchange=issueToken(issue(agentIssue,issueBody("parentToken",execution,"EXCHANGE","knode-knowledge","knowledge:rag:search",Map.of("knowledge","k-exchange","agent","a-exchange"),run)));
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToKnowledge,exchange,"knowledge:rag:search",run),"Bearer "+knowledgeIssue).getStatusCode().value());
        assertEquals(401,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(knowledgeToAgent,execution,"agent:runs:execute",run),"Bearer "+agentIssue).getStatusCode().value());
        var step=new LinkedHashMap<>(run);step.put("stepId","step-1");step.put("checkpointOperationId","checkpoint-1");
        fixture.registerCheckpoint("run-1","step-1","checkpoint-1");
        proof("k-check","knode-knowledge","SAFE_CHECKPOINT","knode-agent",sid,step,executionGrant,root,"3");
        proof("a-check","knode-agent","SAFE_CHECKPOINT","knode-agent",sid,step,executionGrant,root,"3");
        var checkpointBody=issueBody("parentToken",execution,"SAFE_CHECKPOINT","knode-agent","agent:checkpoint:write",Map.of("knowledge","k-check","agent","a-check"),step);
        String checkpoint=issueToken(issue(agentIssue,checkpointBody));
        assertEquals(403,issue(agentIssue,checkpointBody).getStatusCode().value());
        assertEquals(401,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToAgent,checkpoint,"agent:checkpoint:write",step),"Bearer "+agentIssue).getStatusCode().value());
        var cpInput=Map.of("actorToken",agentToAgent,"delegationToken",checkpoint,"resource",step);
        var before=call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+agentIssue);
        assertEquals(200,before.getStatusCode().value());assertTrue(read(before).path("jti").isTextual());
        assertEquals(200,raw(HttpMethod.POST,"/api/blog/auth/logout",null,user).getStatusCode().value());
        assertEquals(401,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(agentToAgent,execution,"agent:runs:execute",run),"Bearer "+agentIssue).getStatusCode().value());
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+agentIssue).getStatusCode().value());
        var verifier=new DelegationTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME);
        var checked=verifier.verifyCheckpoint(checkpoint,"knode-agent");
        var actor=new ServiceTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME)
                .verify(agentToAgent,"knode-agent",Set.of("agent:checkpoint:write"));
        var current=new DelegationTokenVerifier.CurrentProof(checked.grantId(),"42",sid,"blog","knode-agent","knode-agent",
                step,Set.of("agent:checkpoint:write"),checked.expiresAt()+1,checked.expiresAt()+1,true);
        var capability=verifier.authorizeCheckpoint(checked,actor,current,step);
        var receiverFixture=new LocalCheckpointReceiverFixture();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(() -> receiverFixture.commit(capability,"a".repeat(64)));
            var second=executor.submit(() -> receiverFixture.commit(capability,"a".repeat(64)));
            assertEquals(Set.of(CheckpointCommitGuard.Outcome.APPLIED,CheckpointCommitGuard.Outcome.ALREADY_APPLIED),Set.of(first.get(),second.get()));
        } finally {executor.shutdownNow();}
        assertEquals(1,receiverFixture.db.queryForObject("SELECT effects FROM receiver_run WHERE run_id='run-1'",Integer.class));
        assertEquals(403,assertThrows(AuthFailure.class,() -> receiverFixture.commit(capability,"b".repeat(64))).status());
        receiverFixture.db.update("UPDATE receiver_run SET epoch='2',worker='worker-2' WHERE run_id='run-1'");
        assertEquals(403,assertThrows(AuthFailure.class,() -> receiverFixture.commit(capability,"a".repeat(64))).status());
        assertEquals(401,issue(agentIssue,issueBody("grantId",executionGrant,"EXCHANGE","knode-knowledge","knowledge:rag:search",Map.of("knowledge","k-exchange","agent","a-exchange"),run)).getStatusCode().value());
        String next=user();String nextSid=sid(next);assertNotEquals(sid,nextSid);
        assertEquals(401,issue(agentIssue,issueBody("grantId",executionGrant,"EXCHANGE","knode-knowledge","knowledge:rag:search",Map.of("knowledge","k-exchange","agent","a-exchange"),run)).getStatusCode().value());
        fixture.advanceRun("run-1","2","worker-2");fixture.rebindRoot("run-1",null);
        var continued=new LinkedHashMap<>(creation);continued.put("runId","run-1");continued.put("controlEpoch","2");continued.put("workerId","worker-2");
        proof("k-continue","knode-knowledge","CONTINUE","knode-knowledge",nextSid,continued,null,null,"4");
        proof("a-continue","knode-agent","CONTINUE","knode-knowledge",nextSid,continued,null,null,"4");
        String continueToken=issueToken(issue(knowledgeIssue,issueBody("subjectToken",next,"CONTINUE","knode-agent","agent:runs:execute",Map.of("knowledge","k-continue","agent","a-continue"),continued)));
        var continuedClaims=new DelegationTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),Clock.systemUTC()).verifyNormal(continueToken,"knode-agent");
        assertEquals(nextSid,continuedClaims.sid());assertEquals("2",continuedClaims.resource().get("controlEpoch"));
        fixture.rebindRoot("run-1",grant(continueToken));
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(knowledgeToAgent,continueToken,"agent:runs:execute",continued),"Bearer "+agentIssue).getStatusCode().value());
        assertTrue(fixture.authenticatedRequests.get()>=10);
    }
    private record Scenario(String user,String sid,String knowledge,String agent,String actor,String root,String execution,
                            String executionGrant,Map<String,String> run,Map<String,String> step,Instant issuedAt) {}

    private Scenario checkpointScenario(long oldMachineAge,long rootAuthorizationSeconds) throws Exception {
        String user=user(),sid=sid(user);
        String agent=machine("knode-agent","knode-user","auth:delegations:issue","auth:delegations:validate","auth:delegations:validate-checkpoint");
        if (oldMachineAge>0) TIME.set(TIME.instant().plusSeconds(oldMachineAge));
        String knowledge=machine("knode-knowledge","knode-user","auth:delegations:issue");
        String actor=machine("knode-agent","knode-agent","agent:runs:execute","agent:checkpoint:write");
        var creation=Map.of("knowledgeBaseId","42","clientRequestId","time-request");
        proof("k-time-root","knode-knowledge","CREATE","knode-knowledge",sid,creation,null,null,"1");
        fixture.updateWindow("k-time-root","knode-knowledge",TIME.instant().plusSeconds(500),TIME.instant().plusSeconds(rootAuthorizationSeconds));
        String rootToken=issueToken(issue(knowledge,issueBody("subjectToken",user,"CREATE","knode-agent","agent:runs:create",Map.of("knowledge","k-time-root"),creation)));
        String root=grant(rootToken);
        var run=new LinkedHashMap<>(creation);run.put("runId","run-time");run.put("controlEpoch","1");run.put("workerId","worker-time");
        fixture.registerRun("run-time",root,"1","worker-time");
        proof("k-time-exec","knode-knowledge","EXECUTE","knode-agent",sid,run,root,root,"2");
        proof("a-time-exec","knode-agent","EXECUTE","knode-agent",sid,run,root,root,"2");
        String execution=issueToken(issue(agent,issueBody("parentToken",rootToken,"EXECUTE","knode-agent","agent:runs:execute",Map.of("knowledge","k-time-exec","agent","a-time-exec"),run)));
        String executionGrant=grant(execution);
        var step=new LinkedHashMap<>(run);step.put("stepId","step-time");step.put("checkpointOperationId","op-time");
        proof("k-time-check","knode-knowledge","SAFE_CHECKPOINT","knode-agent",sid,step,executionGrant,root,"3");
        proof("a-time-check","knode-agent","SAFE_CHECKPOINT","knode-agent",sid,step,executionGrant,root,"3");
        return new Scenario(user,sid,knowledge,agent,actor,root,execution,executionGrant,run,step,TIME.instant());
    }
    private Map<String,Object> checkpointBody(Scenario c) {
        return issueBody("parentToken",c.execution(),"SAFE_CHECKPOINT","knode-agent","agent:checkpoint:write",
                Map.of("knowledge","k-time-check","agent","a-time-check"),c.step());
    }
    private Map<String,Object> checkpointValidation(Scenario c,String token) {
        return Map.of("actorToken",c.actor(),"delegationToken",token,"resource",c.step());
    }
    private DelegationTokenVerifier verifier() {
        return new DelegationTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME);
    }
    private void registerCheckpoint(Scenario c,long stepSeconds,long leaseSeconds) {
        fixture.registerCheckpoint("run-time","step-time","op-time",c.issuedAt().minusSeconds(1),
                c.issuedAt().plusSeconds(stepSeconds),c.issuedAt().plusSeconds(leaseSeconds));
    }

    @Test void checkpointOutlivesParentButRequiresStartedStepCurrentLeaseAndAtomicReceiver() throws Exception {
        Scenario c=checkpointScenario(0,600);
        var body=checkpointBody(c);
        assertEquals(403,issue(c.agent(),body).getStatusCode().value()); // no started step
        fixture.registerCheckpoint("run-time","step-time","op-time",c.issuedAt().plusSeconds(1),c.issuedAt().plusSeconds(240),c.issuedAt().plusSeconds(230));
        assertEquals(403,issue(c.agent(),body).getStatusCode().value());
        fixture.registerCheckpoint("run-time","step-time","op-time",c.issuedAt().minusSeconds(1),c.issuedAt().plusSeconds(240),c.issuedAt());
        assertEquals(403,issue(c.agent(),body).getStatusCode().value());
        registerCheckpoint(c,240,230);fixture.revokeLease("run-time");
        assertEquals(403,issue(c.agent(),body).getStatusCode().value());
        registerCheckpoint(c,240,230);fixture.revokeStep("run-time");
        assertEquals(403,issue(c.agent(),body).getStatusCode().value());
        registerCheckpoint(c,240,230);
        var wrongResource=new LinkedHashMap<>(c.step());wrongResource.put("workerId","wrong-worker");
        var wrongBody=new LinkedHashMap<>(body);wrongBody.put("expectedResource",wrongResource);
        assertEquals(403,issue(c.agent(),wrongBody).getStatusCode().value());
        var fromGrant=issueBody("grantId",c.executionGrant(),"SAFE_CHECKPOINT","knode-agent","agent:checkpoint:write",Map.of("knowledge","k-time-check","agent","a-time-check"),c.step());
        assertEquals(403,issue(c.agent(),fromGrant).getStatusCode().value());
        assertEquals(401,issue(c.knowledge(),body).getStatusCode().value());
        var unauthorized=new LinkedHashMap<>(body);unauthorized.put("permissions",List.of("agent:runs:execute"));
        assertEquals(400,issue(c.agent(),unauthorized).getStatusCode().value());
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",
                validation(c.actor(),c.execution(),"agent:checkpoint:write",c.run()),"Bearer "+c.agent()).getStatusCode().value());
        when(reader.find(anyString())).thenThrow(new SessionStore.Unavailable("synthetic"));
        assertEquals(503,issue(c.agent(),body).getStatusCode().value());
        assertEquals(503,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",
                validation(c.actor(),c.execution(),"agent:runs:execute",c.run()),"Bearer "+c.agent()).getStatusCode().value());
        doAnswer(inv -> Optional.ofNullable(sessions.get(inv.getArgument(0)))).when(reader).find(anyString());
        var parent=verifier().verifyNormal(c.execution(),"knode-agent");
        String checkpoint=issueToken(issue(c.agent(),body));
        var checked=verifier().verifyCheckpoint(checkpoint,"knode-agent");
        assertEquals(c.issuedAt().plusSeconds(230).getEpochSecond(),checked.expiresAt());
        assertTrue(checked.expiresAt()>parent.expiresAt());
        assertEquals(403,issue(c.agent(),body).getStatusCode().value());
        assertEquals(200,raw(HttpMethod.POST,"/api/blog/auth/logout",null,c.user()).getStatusCode().value());
        assertEquals(401,issue(c.agent(),body).getStatusCode().value());
        TIME.set(Instant.ofEpochSecond(parent.expiresAt()+1));
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier().verifyNormal(c.execution(),"knode-agent")).status());
        assertEquals(401,issue(c.agent(),body).getStatusCode().value());
        assertEquals(401,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(c.actor(),c.execution(),"agent:runs:execute",c.run()),"Bearer "+c.agent()).getStatusCode().value());
        assertEquals(401,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(c.actor(),checkpoint,"agent:checkpoint:write",c.step()),"Bearer "+c.agent()).getStatusCode().value());
        var cpInput=checkpointValidation(c,checkpoint);
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        var wrong=new LinkedHashMap<>(c.step());wrong.put("knowledgeBaseId","43");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",Map.of("actorToken",c.actor(),"delegationToken",checkpoint,"resource",wrong),"Bearer "+c.agent()).getStatusCode().value());
        fixture.mode.set("UNKNOWN_SCHEMA");
        assertEquals(503,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        fixture.mode.set("NORMAL");
        var actorPrincipal=new ServiceTokenVerifier(Map.of("test-key",(RSAPublicKey)keys.getPublic()),TIME)
                .verify(c.actor(),"knode-agent",Set.of("agent:checkpoint:write"));
        var currentProof=new DelegationTokenVerifier.CurrentProof(checked.grantId(),"42",c.sid(),"blog","knode-agent","knode-agent",
                c.step(),Set.of("agent:checkpoint:write"),checked.expiresAt(),checked.expiresAt(),true);
        var capability=verifier().authorizeCheckpoint(checked,actorPrincipal,currentProof,c.step());
        var receiver=new LocalCheckpointReceiverFixture("run-time","1","worker-time");
        assertEquals(CheckpointCommitGuard.Outcome.APPLIED,receiver.commit(capability,"a".repeat(64)));
        assertEquals(CheckpointCommitGuard.Outcome.ALREADY_APPLIED,receiver.commit(capability,"a".repeat(64)));
        assertEquals(403,assertThrows(AuthFailure.class,() -> receiver.commit(capability,"b".repeat(64))).status());
        assertEquals(1,receiver.db.queryForObject("SELECT effects FROM receiver_run WHERE run_id='run-time'",Integer.class));
        registerCheckpoint(c,240,70);
        TIME.set(c.issuedAt().plusSeconds(69));
        assertEquals(200,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        TIME.set(c.issuedAt().plusSeconds(70));
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        registerCheckpoint(c,240,230);
        fixture.revokeLease("run-time");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        registerCheckpoint(c,240,230);fixture.revokeStep("run-time");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        registerCheckpoint(c,240,230);fixture.advanceRun("run-time","1","worker-new");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        fixture.advanceRun("run-time","2","worker-time");
        assertEquals(403,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate-checkpoint",cpInput,"Bearer "+c.agent()).getStatusCode().value());
        TIME.set(c.issuedAt().plusSeconds(230));
        assertEquals(401,assertThrows(AuthFailure.class,() -> verifier().verifyCheckpoint(checkpoint,"knode-agent")).status());
    }

    @Test void checkpointCannotBeIssuedIfParentExpiresDuringProofResolution() throws Exception {
        Scenario c=checkpointScenario(0,600);
        registerCheckpoint(c,500,500);
        long parentExpiry=verifier().verifyNormal(c.execution(),"knode-agent").expiresAt();
        fixture.afterSafeProof.set(() -> TIME.set(Instant.ofEpochSecond(parentExpiry)));
        assertEquals(401,issue(c.agent(),checkpointBody(c)).getStatusCode().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM auth_delegation_grant WHERE purpose='SAFE_CHECKPOINT'",Integer.class));
        assertEquals(401,issue(c.agent(),checkpointBody(c)).getStatusCode().value());
    }

    @Test void checkpointExpiryIsTheEarliestActualSafetyDeadline() throws Exception {
        for (String limit:List.of("maximum","root-authorization","session","knowledge-proof","knowledge-authorization","agent-proof","started-step","lease","caller-machine")) {
            setup();
            long age="caller-machine".equals(limit)?200:0;
            Scenario c=checkpointScenario(age,"root-authorization".equals(limit)?90:600);
            long bound=switch (limit) {
                case "root-authorization" -> 90;
                case "session" -> 95;
                case "knowledge-proof" -> 100;
                case "knowledge-authorization" -> 105;
                case "agent-proof" -> 110;
                case "started-step" -> 115;
                case "lease" -> 120;
                case "caller-machine" -> 100;
                default -> 300;
            };
            if ("session".equals(limit)) sessions.compute(c.sid(),(sid,old) ->
                    new SessionStore.Session(old.schemaVersion(),old.sid(),old.userId(),old.scope(),old.status(),c.issuedAt().plusSeconds(bound).getEpochSecond()));
            if ("knowledge-proof".equals(limit)) fixture.updateWindow("k-time-check","knode-knowledge",c.issuedAt().plusSeconds(bound),c.issuedAt().plusSeconds(600));
            if ("knowledge-authorization".equals(limit)) fixture.updateWindow("k-time-check","knode-knowledge",c.issuedAt().plusSeconds(500),c.issuedAt().plusSeconds(bound));
            if ("agent-proof".equals(limit)) fixture.updateWindow("a-time-check","knode-agent",c.issuedAt().plusSeconds(bound),c.issuedAt().plusSeconds(600));
            registerCheckpoint(c,"started-step".equals(limit)?bound:500,"lease".equals(limit)?bound:500);
            String checkpoint=issueToken(issue(c.agent(),checkpointBody(c)));
            var verified=verifier().verifyCheckpoint(checkpoint,"knode-agent");
            assertEquals(c.issuedAt().plusSeconds(bound).getEpochSecond(),verified.expiresAt(),limit);
            TIME.set(c.issuedAt().plusSeconds(bound-1));
            assertEquals(verified.jti(),verifier().verifyCheckpoint(checkpoint,"knode-agent").jti(),limit);
            TIME.set(c.issuedAt().plusSeconds(bound));
            assertEquals(401,assertThrows(AuthFailure.class,() -> verifier().verifyCheckpoint(checkpoint,"knode-agent"),limit).status());
        }
    }

    @Test void rejectsOversizedMachineJwtWithoutTruncatingPermissions() throws Exception {
        String basic="Basic "+Base64.getEncoder().encodeToString(("knode-heavy:"+SECRET).getBytes(StandardCharsets.UTF_8));
        var response=call(HttpMethod.POST,"/internal/v1/auth/service-tokens",Map.of("audience","knode-agent","permissions",HEAVY),basic);
        assertEquals(400,response.getStatusCode().value());
        assertFalse(read(response).has("accessToken"));
    }
    @Test void rejectsForgedFieldsAndUnverifiableOrStaleAuthority() throws Exception {
        String user=user(),sid=sid(user),caller=machine("knode-knowledge","knode-user","auth:delegations:issue");
        var resource=Map.of("knowledgeBaseId","42","clientRequestId","request-2");
        proof("k-root","knode-knowledge","CREATE","knode-knowledge",sid,resource,null,null,"2");
        var request=issueBody("subjectToken",user,"CREATE","knode-agent","agent:runs:create",Map.of("knowledge","k-root"),resource);
        var forged=new LinkedHashMap<>(request);forged.put("userId","43");
        assertEquals(400,issue(caller,forged).getStatusCode().value());
        var multiple=new LinkedHashMap<>(request);multiple.put("grantId",UUID.randomUUID().toString());
        assertEquals(400,issue(caller,multiple).getStatusCode().value());
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(caller);
        assertEquals(400,http.exchange("/internal/v1/auth/delegations",HttpMethod.POST,new HttpEntity<>(" ".repeat(32769),headers),String.class).getStatusCode().value());
        for (String mode:List.of("UNKNOWN_SCHEMA","OVERSIZE","MISSING","WRONG_ACTOR","REDIRECT")) {
            fixture.mode.set(mode);assertEquals(503,issue(caller,request).getStatusCode().value(),mode);
        }
        fixture.mode.set("DENIED");assertEquals(403,issue(caller,request).getStatusCode().value());
        fixture.mode.set("TIMEOUT");assertEquals(503,issue(caller,request).getStatusCode().value());
        fixture.mode.set("TRICKLE");long start=System.nanoTime();
        assertEquals(503,issue(caller,request).getStatusCode().value());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<5);
        fixture.mode.set("NORMAL");String rootToken=issueToken(issue(caller,request));
        String receiver=machine("knode-agent","knode-user","auth:delegations:validate");
        String actor=machine("knode-knowledge","knode-agent","agent:runs:create");
        fixture.updateRevision("k-root","knode-knowledge","1");
        assertEquals(503,call(HttpMethod.POST,"/internal/v1/auth/delegations:validate",validation(actor,rootToken,"agent:runs:create",resource),"Bearer "+receiver).getStatusCode().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM auth_delegation_grant_audit WHERE action<>'ISSUE'",Integer.class));
    }
}
