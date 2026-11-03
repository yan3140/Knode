package com.knode.user;

import com.knode.user.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class ProductionWiringTest {
    @Autowired SessionStore.Reader reader;
    @Autowired SessionStore.Writer writer;
    @Autowired UserTokens tokens;
    @Autowired org.springframework.context.ApplicationContext context;
    static java.net.ServerSocket unwantedConnections;
    @Autowired @Qualifier("authReadConnection") LettuceConnectionFactory readConnection;
    @Autowired @Qualifier("authWriteConnection") LettuceConnectionFactory writeConnection;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);var pair=generator.generateKeyPair();
        Path priv=Files.createTempFile("wiring-private-",".pem"), pub=Files.createTempFile("wiring-public-",".pem");
        priv.toFile().deleteOnExit();pub.toFile().deleteOnExit();
        Files.writeString(priv,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        Files.writeString(pub,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        unwantedConnections=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));
        registry.add("USER_NACOS_SERVER_ADDR",() -> "127.0.0.1:"+unwantedConnections.getLocalPort());
        registry.add("USER_OTLP_ENDPOINT",() -> "http://127.0.0.1:"+unwantedConnections.getLocalPort()+"/v1/traces");
        registry.add("spring.datasource.url",() -> "jdbc:h2:mem:wiring;MODE=MySQL");
        registry.add("spring.datasource.username",() -> "sa");registry.add("spring.datasource.password",() -> "");
        registry.add("KNODE_AUTH_ENVIRONMENT",() -> "test");
        registry.add("KNODE_AUTH_REVOCATION_INITIAL_MS",() -> "600000");
        registry.add("KNODE_AUTH_SIGNING_KID",() -> "test-key");
        registry.add("KNODE_AUTH_SIGNING_PRIVATE_FILE",() -> priv.toString());
        registry.add("KNODE_AUTH_SIGNING_PUBLIC_FILE",() -> pub.toString());
        for (String role:new String[]{"READ","WRITE"}) {
            String prefix="KNODE_AUTH_REDIS_"+role+"_";
            registry.add(prefix+"HOST",() -> "127.0.0.1");
            registry.add(prefix+"PORT",() -> role.equals("READ")?"1":"2");
            registry.add(prefix+"USERNAME",() -> "test-"+role.toLowerCase());
            registry.add(prefix+"PASSWORD",() -> "test-only-value");
            registry.add(prefix+"TLS",() -> "true");
        }
    }
    @Test void configuresRealAdaptersAndDistinctConnectionsWithoutConnecting() {
        assertInstanceOf(RedisSessionReader.class,reader);
        assertInstanceOf(RedisSessionWriter.class,writer);
        assertNotNull(tokens);
        assertEquals(1,readConnection.getStandaloneConfiguration().getPort());
        assertEquals(2,writeConnection.getStandaloneConfiguration().getPort());
        assertTrue(readConnection.isUseSsl());assertTrue(writeConnection.isUseSsl());
    }
    @Test void disabledDiscoveryConfigAndObservabilityDoNotOpenTheirConfiguredEndpoint() throws Exception {
        assertTrue(context.getBeansOfType(io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.class).isEmpty());
        unwantedConnections.setSoTimeout(500);
        assertThrows(java.net.SocketTimeoutException.class,() -> unwantedConnections.accept().close());
    }
    @org.junit.jupiter.api.AfterAll static void closeUnwantedConnections() throws Exception {
        if (unwantedConnections!=null) unwantedConnections.close();
    }

}
