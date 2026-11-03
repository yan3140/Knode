package com.knode.user;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalTraceProbeTest {
    private static final String TRACE="11111111111111111111111111111111";
    private static final String SPAN="2222222222222222";
    private static final String PRODUCER="00-"+TRACE+"-"+SPAN+"-01";

    private static String body(boolean user,boolean gateway) {
        String spans=(user?"{\"traceID\":\""+TRACE+"\",\"spanID\":\""+SPAN+"\",\"processID\":\"u\"}":"")
                +(user && gateway?",":"")
                +(gateway?"{\"traceID\":\""+TRACE+"\",\"spanID\":\"3333333333333333\",\"processID\":\"g\"}":"");
        return "{\"data\":[{\"traceID\":\""+TRACE+"\",\"spans\":["+spans
                +"],\"processes\":{\"u\":{\"serviceName\":\"knode-user\"},\"g\":{\"serviceName\":\"knode-gateway\"}}}]}";
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status,bytes.length);
        try (var output=exchange.getResponseBody()) {output.write(bytes);}
    }

    @Test void originRequiresAnExplicitLoopbackRootWithoutCredentials() {
        assertEquals("http://127.0.0.1:12345",ExternalEventGuard.checkJaegerEndpoint("http://127.0.0.1:12345/"));
        for (String endpoint:new String[]{"http://localhost:12345","http://192.0.2.1:12345","http://127.0.0.1",
                "https://127.0.0.1:12345","http://user@127.0.0.1:12345","http://127.0.0.1:12345/api/traces",
                "http://127.0.0.1:12345?x=1","http://127.0.0.1:12345/#x"})
            assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.checkJaegerEndpoint(endpoint));
    }

    @Test void waitsThrough404EmptyAndUserOnlyUntilBothServicesAppear() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var count=new AtomicInteger();var path=new AtomicReference<String>();
        server.createContext("/api/traces/",exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            int attempt=count.incrementAndGet();
            int status=attempt==1?404:200;
            String content=attempt<=2?"{\"data\":[]}":body(true,attempt>=4);
            respond(exchange,status,content.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        try {
            new ExternalTraceProbe("http://127.0.0.1:"+server.getAddress().getPort())
                    .awaitProducer(PRODUCER,true,Duration.ofSeconds(5));
            assertTrue(count.get()>=4);assertEquals("/api/traces/"+TRACE,path.get());
        } finally {server.stop(0);}
    }

    @Test void directRecoveryNeedsExactUserSpanAndTimesOutOnMissingSpans() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var content=new AtomicReference<>(body(true,false));
        server.createContext("/api/traces/",exchange -> respond(exchange,200,content.get().getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            var probe=new ExternalTraceProbe("http://127.0.0.1:"+server.getAddress().getPort());
            probe.awaitProducer(PRODUCER,false,Duration.ofSeconds(2));
            assertThrows(IllegalStateException.class,() -> probe.awaitProducer(PRODUCER,true,Duration.ofMillis(500)));
            content.set(body(false,true));
            assertThrows(IllegalStateException.class,() -> probe.awaitProducer(PRODUCER,false,Duration.ofMillis(500)));
        } finally {server.stop(0);}
    }

    @Test void stalledResponseBodyCannotExceedTheOverallDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/traces/",exchange -> {
            try {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                release.await(5,java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {Thread.currentThread().interrupt();}
            catch (java.io.IOException ignored) {}
            finally {exchange.close();}
        });
        server.start();
        try {
            var probe=new ExternalTraceProbe("http://127.0.0.1:"+server.getAddress().getPort());
            assertTimeout(Duration.ofSeconds(3),() ->
                    assertThrows(IllegalStateException.class,() -> probe.awaitProducer(PRODUCER,false,Duration.ofMillis(500))));
        } finally {release.countDown();server.stop(0);}
    }

    @Test void responseSizeIsBounded() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/api/traces/",exchange -> {
            try {respond(exchange,200,new byte[1024*1024+1]);} catch (java.io.IOException ignored) {}
        });
        server.start();
        try {
            var failure=assertThrows(IllegalStateException.class,() ->
                    new ExternalTraceProbe("http://127.0.0.1:"+server.getAddress().getPort())
                            .awaitProducer(PRODUCER,false,Duration.ofSeconds(3)));
            assertTrue(failure.getMessage().contains("limit"));
        } finally {server.stop(0);}
    }
}
