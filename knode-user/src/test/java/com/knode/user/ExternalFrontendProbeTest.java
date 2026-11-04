package com.knode.user;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalFrontendProbeTest {
    @Test void usesHttp11WithoutAnUpgradeAndReportsHttpStatus() throws Exception {
        var upgrade=new AtomicBoolean();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/",exchange -> {
            upgrade.set(exchange.getRequestHeaders().containsKey("Upgrade"));
            exchange.sendResponseHeaders(503,-1);exchange.close();
        });
        server.start();
        try {
            var result=new ExternalFrontendProbe().observe(server.getAddress().getPort());
            assertEquals(503,result.status());assertNull(result.errorType());assertTrue(result.listening());
            assertFalse(upgrade.get(),"Frontend probe must not trigger h2c");
        } finally {server.stop(0);}
    }
    @Test void reportsOnlySafeExceptionTypeAndSelectedPortState() throws Exception {
        int port;try (ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {port=socket.getLocalPort();}
        var result=new ExternalFrontendProbe().observe(port);
        assertNull(result.status());assertNotNull(result.errorType());
        assertTrue(result.errorType().matches("[A-Za-z0-9]+"));assertFalse(result.listening());
    }
}
