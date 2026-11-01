package com.knode.gateway;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** A loopback RESP test peer, not a Redis server or an existing data instance. */
class RedisSessionReaderTest {
    @Test void reactiveReaderUsesNamespacedGetAndNoWrites() throws Exception {
        try (RespPeer peer = new RespPeer()) {
            var factory = new AuthenticationConfiguration().authRedisConnection(
                    "127.0.0.1", peer.port(), "test-reader", "test-password", 0, 1000, false);
            factory.afterPropertiesSet();
            factory.start();
            try {
                SessionReader reader = new AuthenticationConfiguration().sessionReader(factory, "isolated");
                assertThat(reader.get("sid-test").block(Duration.ofSeconds(5))).isEqualTo("sample-session");
                reactor.test.StepVerifier.create(reader.get("absent")).verifyComplete();
                reader.checkAvailability().block(Duration.ofSeconds(5));
                assertThat(peer.commands).contains("GET knode:isolated:v1:auth:session:sid-test",
                        "GET knode:isolated:v1:auth:session:absent",
                        "GET knode:isolated:v1:auth:session:__readiness__");
                assertThat(peer.commands).allMatch(x -> x.startsWith("GET ") ||
                        java.util.Set.of("HELLO", "AUTH", "CLIENT", "PING", "SELECT").contains(x));
            } finally { factory.destroy(); }
        }
    }

    static final class RespPeer implements AutoCloseable {
        final ServerSocket server;
        final List<String> commands = new CopyOnWriteArrayList<>();
        final Thread thread;
        RespPeer() throws Exception {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-redis-peer"); thread.setDaemon(true); thread.start();
        }
        int port() { return server.getLocalPort(); }
        void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    while (!socket.isClosed()) {
                        String head = line(in);
                        if (head == null) break;
                        if (!head.startsWith("*")) throw new IllegalStateException("expected RESP array");
                        int count = Integer.parseInt(head.substring(1)); List<String> args = new ArrayList<>();
                        for (int i=0; i<count; i++) {
                            String size = line(in);
                            if (size == null || !size.startsWith("$")) throw new IllegalStateException("expected bulk");
                            int length = Integer.parseInt(size.substring(1));
                            byte[] data = in.readNBytes(length);
                            if (data.length != length || in.read() != 13 || in.read() != 10) throw new IllegalStateException("bad bulk");
                            args.add(new String(data, StandardCharsets.UTF_8));
                        }
                        String command = args.get(0).toUpperCase(java.util.Locale.ROOT);
                        if (command.equals("GET")) commands.add("GET " + args.get(1)); else commands.add(command);
                        String response;
                        if (command.equals("HELLO")) response = "-ERR unknown command HELLO\r\n";
                        else if (command.equals("GET")) response = args.get(1).endsWith("sid-test") ?
                                "$14\r\nsample-session\r\n" : "$-1\r\n";
                        else if (command.equals("PING")) response = "+PONG\r\n";
                        else response = "+OK\r\n";
                        out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush();
                    }
                } catch (Exception ignored) { if (server.isClosed()) return; }
            }
        }
        static String line(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == 13) { if (in.read() != 10) throw new IllegalStateException("bad line"); return out.toString(StandardCharsets.UTF_8); }
                out.write(c);
            }
            return null;
        }
        @Override public void close() throws Exception { server.close(); thread.join(2000); }
    }
}
