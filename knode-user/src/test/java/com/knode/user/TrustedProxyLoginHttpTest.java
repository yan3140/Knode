package com.knode.user;

import com.knode.user.auth.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "management.server.port=0","USER_TRUSTED_PROXY_CIDRS=127.0.0.0/8,10.0.0.0/8"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustedProxyLoginHttpTest {
    @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;@Autowired DataSource data;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @MockitoBean BCryptPasswordEncoder encoder;
    @MockitoBean SessionStore.Reader reader;@MockitoBean SessionStore.Writer writer;
    @MockitoBean(name="authReadConnection") LettuceConnectionFactory readConnection;
    @MockitoBean(name="authWriteConnection") LettuceConnectionFactory writeConnection;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        Path priv=Files.createTempFile("trusted-login-private-",".pem"),pub=Files.createTempFile("trusted-login-public-",".pem");
        priv.toFile().deleteOnExit();pub.toFile().deleteOnExit();
        Files.writeString(priv,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(pub,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",priv::toString);
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",pub::toString);registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "600000");registry.add("KNODE_SESSION_EVENTS_INITIAL_MS",() -> "600000");
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:trusted_login;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username",() -> "sa");registry.add("spring.datasource.password",() -> "");
    }
    @BeforeAll void schema() {new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(data);}
    @BeforeEach void setup() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(
                org.apache.hc.client5.http.impl.classic.HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().build()));
        db.update("DELETE FROM user_login_attempt_window");db.update("DELETE FROM sys_user");
        for (int i=0;i<21;i++) db.update("INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(?,?,?,'hash',?,'0','0','2',0)",
                100+i,"user"+i,"nick"+i,"u"+i+"@example.invalid");
        when(encoder.matches(any(),anyString())).thenReturn(false);
    }
    @Test void trustedChainUsesFirstUntrustedHopAndIgnoresForgedEarlierValues() {
        for (int i=0;i<21;i++) {
            var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add("X-Forwarded-For","198.51.100."+(i+1)+", 203.0.113.9, 10.2.3.4");
            var response=http.exchange("/api/blog/auth/login",HttpMethod.POST,
                    new HttpEntity<>("{\"userName\":\"user"+i+"\",\"password\":\"wrong\"}",headers),String.class);
            assertEquals(i==20?429:200,response.getStatusCode().value());
        }
    }
    @Test void trustedMalformedOrRepeatedChainsFailClosed() throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        var uri=java.net.URI.create("http://127.0.0.1:"+port+"/api/blog/auth/login");
        for (var headers:List.of(List.of("invalid-host"),List.of("198.51.100.1","203.0.113.2"))) {
            var b=java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json");
            headers.forEach(value -> b.header("X-Forwarded-For",value));
            var r=b.POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"userName\":\"user0\",\"password\":\"wrong\"}")).build();
            assertEquals(400,client.send(r,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_login_attempt_window",Integer.class));
    }
}
