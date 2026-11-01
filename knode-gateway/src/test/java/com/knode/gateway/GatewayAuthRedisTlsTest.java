package com.knode.gateway;

import io.lettuce.core.SslVerifyMode;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayAuthRedisTlsTest {
    @TempDir Path directory;

    @Test void environmentAliasDefaultsToTlsRequiresExplicitPlaintextAndRejectsBadSettings() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Path pem = directory.resolve("public.pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(generator.generateKeyPair().getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
        var yaml = new YamlPropertySourceLoader().load("gatewayYaml", new ClassPathResource("application.yml")).get(0);
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(AuthenticationConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(yaml))
                .withPropertyValues("knode.gateway.auth.configured=true",
                        "knode.gateway.auth.public-keys=test=" + pem,
                        "knode.gateway.auth.environment=verify-local",
                        "knode.gateway.auth.redis-host=127.0.0.1",
                        "knode.gateway.auth.redis-port=16379",
                        "knode.gateway.auth.redis-username=test-reader",
                        "knode.gateway.auth.redis-password=test-only");
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var cfg = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            assertThat(cfg.isUseSsl()).isTrue();
            assertThat(cfg.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
            assertThat(cfg.isStartTls()).isFalse();
        });
        runner.withPropertyValues("KNODE_GATEWAY_AUTH_REDIS_TLS=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LettuceConnectionFactory.class).getClientConfiguration().isUseSsl()).isFalse();
        });
        runner.withPropertyValues("KNODE_GATEWAY_AUTH_REDIS_TLS=true").run(context -> {
            assertThat(context).hasNotFailed();
            var cfg = context.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            assertThat(cfg.isUseSsl()).isTrue();
            assertThat(cfg.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
        });
        runner.withPropertyValues("KNODE_GATEWAY_AUTH_REDIS_TLS=not-a-boolean")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("knode.gateway.auth.redis-port=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void selfSignedTlsPeerIsRejectedAndReadinessIsOutOfService() throws Exception {
        try (UntrustedTlsPeer peer = new UntrustedTlsPeer(directory)) {
            var config = new AuthenticationConfiguration();
            var factory = config.authRedisConnection("127.0.0.1", peer.port(), "test-reader", "test-only", 0, 1000, true);
            factory.afterPropertiesSet(); factory.start();
            try {
                SessionReader reader = config.sessionReader(factory, "verify-local");
                Throwable failure = catchThrowable(() -> reader.checkAvailability().block(Duration.ofSeconds(8)));
                assertThat(failure).isNotNull().hasStackTraceContaining("SSLHandshakeException")
                        .hasStackTraceContaining("PKIX path building failed");
                @SuppressWarnings("unchecked") ObjectProvider<AuthMaterial> materials = mock(ObjectProvider.class);
                @SuppressWarnings("unchecked") ObjectProvider<SessionReader> readers = mock(ObjectProvider.class);
                when(materials.getIfAvailable()).thenReturn(new AuthMaterial(null, Duration.ofSeconds(4)));
                when(readers.getIfAvailable()).thenReturn(reader);
                var health = new AuthenticationHealth(materials, readers).health().block(Duration.ofSeconds(8));
                assertThat(health).isNotNull();
                assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                assertThat(peer.successfulHandshakes.get()).isZero();
            } finally { factory.destroy(); }
        }
    }

    /** The certificate and private key are generated solely in the JUnit temporary directory. */
    static final class UntrustedTlsPeer implements AutoCloseable {
        final SSLServerSocket server;
        final Thread thread;
        final AtomicInteger successfulHandshakes = new AtomicInteger();

        UntrustedTlsPeer(Path dir) throws Exception {
            Path store = dir.resolve("ephemeral-peer.p12");
            String password = java.util.UUID.randomUUID().toString();
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            Process keygen = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "ephemeral",
                    "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", password, "-keypass", password, "-noprompt")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!keygen.waitFor(30, TimeUnit.SECONDS)) {
                keygen.destroyForcibly(); throw new IllegalStateException("Key generation timed out");
            }
            if (keygen.exitValue() != 0) throw new IllegalStateException("Ephemeral key generation failed");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) { keys.load(in, password.toCharArray()); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password.toCharArray());
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(managers.getKeyManagers(), null, null);
            server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(
                    0, 10, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.setSoTimeout(5000); socket.startHandshake(); successfulHandshakes.incrementAndGet();
                    } catch (Exception ignored) { if (server.isClosed()) return; }
                }
            }, "untrusted-test-tls-peer");
            thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); thread.join(2000); }
    }
}
