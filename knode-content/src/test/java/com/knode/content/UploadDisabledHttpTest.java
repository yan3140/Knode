package com.knode.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class UploadDisabledHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ACTOR="9007199254740993";
    static final AtomicInteger CALLS=new AtomicInteger();
    static final HttpServer PROVIDER=provider();
    @LocalServerPort int port;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
        p.add("KNODE_CONTENT_UPLOAD_ENABLED",() -> "false");
        p.add("KNODE_CONTENT_UPLOAD_ENDPOINT",() -> "");
        p.add("KNODE_CONTENT_UPLOAD_REGION",() -> "");
        p.add("KNODE_CONTENT_UPLOAD_BUCKET",() -> "");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); }
    @Test void unconfiguredUploadStartsButReturnsUnavailableAfterAuthorization() throws Exception {
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
        String body="--b\r\nContent-Disposition: form-data; name=\"img\"; filename=\"x.png\"\r\nContent-Type: image/png\r\n\r\nabc\r\n--b--\r\n";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/upload"))
                .header("token",AuthenticationHttpTest.token("admin",600)).header("Content-Type","multipart/form-data; boundary=b")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=AuthenticationHttpTest.HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(503,response.statusCode()); assertEquals(500,JSON.readTree(response.body()).path("code").asInt());
        assertEquals(1,CALLS.get());
    }
    private static HttpServer provider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/admin/auth/me",e -> {
                try {
                    CALLS.incrementAndGet();
                    byte[] body=JSON.writeValueAsBytes(Map.of("code",200,"msg","ok","data",
                            Map.of("roles",List.of("admin"),"permissions",List.of("content:upload:add"),"user",Map.of("id",ACTOR))));
                    e.sendResponseHeaders(200,body.length); try(var out=e.getResponseBody()) { out.write(body); }
                } catch(Exception ignored) { e.close(); }
            });
            server.start(); return server;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
}
