package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.Session;
import com.knode.auth.SessionCodec;
import com.knode.content.upload.UploadFailure;
import com.knode.content.upload.UploadStorage;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KNODE_MANAGEMENT_PORT=0","KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class UploadHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ACTOR="9007199254740993", BOUNDARY="knode-boundary";
    static final AtomicReference<List<String>> PERMS=new AtomicReference<>(List.of("content:upload:add"));
    static final AtomicReference<String> TOKEN=new AtomicReference<>(), MODE=new AtomicReference<>("ok");
    static final AtomicInteger USER_CALLS=new AtomicInteger();
    static final AtomicBoolean PROTOCOL=new AtomicBoolean(true);
    static final ExecutorService EXEC=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
    static final HttpServer PROVIDER=provider();
    @LocalServerPort int port;
    @MockBean UploadStorage storage;
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_USER_ORIGIN",() -> "http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        p.add("KNODE_USER_SERVICE_ID",() -> "knode-content");
        p.add("KNODE_USER_BOOTSTRAP_SECRET",() -> "synthetic-secret");
        p.add("KNODE_CONTENT_UPLOAD_ENABLED",() -> "true");
        p.add("KNODE_CONTENT_UPLOAD_ENDPOINT",() -> "https://oss-cn-test.aliyuncs.com");
        p.add("KNODE_CONTENT_UPLOAD_REGION",() -> "cn-test");
        p.add("KNODE_CONTENT_UPLOAD_BUCKET",() -> "fixture-bucket");
    }
    @AfterAll static void stop() { PROVIDER.stop(0); EXEC.shutdownNow(); }
    @BeforeEach void resetState() {
        TOKEN.set(AuthenticationHttpTest.token("admin",600)); PERMS.set(List.of("content:upload:add")); MODE.set("ok"); USER_CALLS.set(0); PROTOCOL.set(true);
        adminSession(); reset(storage);
        when(storage.put(anyString(),anyString(),any(byte[].class))).thenReturn("https://fixture.invalid/signed");
    }
    static void adminSession() {
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"admin","ACTIVE",Instant.now().getEpochSecond()+1800)));
    }
    record Item(String name,String filename,String contentType,byte[] bytes) {}
    static byte[] image(String format,int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,format,out)); image.flush(); return out.toByteArray();
    }
    static byte[] paddedPng(int targetLength) throws Exception {
        byte[] source=image("png",1,1);
        int padding=targetLength-source.length-12;
        assertTrue(padding>=0);
        var out=new ByteArrayOutputStream(); var data=new java.io.DataOutputStream(out);
        data.write(source,0,source.length-12);
        data.writeInt(padding);
        byte[] type="vpAg".getBytes(StandardCharsets.US_ASCII), extra=new byte[padding];
        data.write(type); data.write(extra);
        var crc=new java.util.zip.CRC32(); crc.update(type); crc.update(extra); data.writeInt((int)crc.getValue());
        data.write(source,source.length-12,12); data.flush();
        assertEquals(targetLength,out.size()); return out.toByteArray();
    }
    static byte[] form(Item... items) throws Exception {
        var out=new ByteArrayOutputStream();
        for(Item item:items) {
            out.write(("--"+BOUNDARY+"\r\nContent-Disposition: form-data; name=\""+item.name()+"\"; filename=\""+item.filename()+"\"\r\nContent-Type: "+item.contentType()+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(item.bytes()); out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("--"+BOUNDARY+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
    HttpResponse<String> send(String path,String token,String contentType,byte[] body,boolean spoof) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if(token!=null) b.header("token",token);
        if(contentType!=null) b.header("Content-Type",contentType);
        if(spoof) b.header("X-User-Id","42");
        return AuthenticationHttpTest.HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(String path,String token,Item... items) throws Exception {
        return send(path,token,"multipart/form-data; boundary="+BOUNDARY,form(items),false);
    }
    static JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }

    @Test void validPngAndJpegUseDetectedTypeAndRandomSafeObjectName() throws Exception {
        byte[] png=image("png",2,2);
        var first=upload("/api/admin/upload",TOKEN.get(),new Item("img","../../secret.svg","text/html",png));
        assertEquals(200,first.statusCode()); assertEquals(200,json(first).path("code").asInt());
        assertEquals("\u4e0a\u4f20\u6210\u529f",json(first).path("msg").asText());
        assertEquals("https://fixture.invalid/signed",json(first).path("data").asText());
        ArgumentCaptor<String> names=ArgumentCaptor.forClass(String.class), types=ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytes=ArgumentCaptor.forClass(byte[].class);
        verify(storage).put(names.capture(),types.capture(),bytes.capture());
        assertTrue(names.getValue().matches("[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.png"));
        assertFalse(names.getValue().contains("secret")); assertEquals("image/png",types.getValue()); assertArrayEquals(png,bytes.getValue());
        reset(storage); when(storage.put(anyString(),anyString(),any(byte[].class))).thenReturn("https://fixture.invalid/signed");
        byte[] jpg=image("jpeg",3,2);
        var second=upload("/upload",TOKEN.get(),new Item("img","wrong.png","image/png",jpg));
        assertEquals(200,second.statusCode()); assertEquals(200,json(second).path("code").asInt());
        verify(storage).put(names.capture(),types.capture(),bytes.capture());
        assertTrue(names.getValue().endsWith(".jpg")); assertEquals("image/jpeg",types.getValue()); assertArrayEquals(jpg,bytes.getValue());
    }
    @Test void everyPermissionIsLiveAndAuthorizationFailureNeverTouchesStorage() throws Exception {
        byte[] png=image("png",1,1); Item valid=new Item("img","x.png","image/png",png);
        for(String permission:List.of("content:upload:add","content:article:add","content:article:edit","*:*:*")) {
            PERMS.set(List.of(permission));
            assertEquals(200,upload("/upload",TOKEN.get(),valid).statusCode());
        }
        verify(storage,times(4)).put(anyString(),anyString(),any(byte[].class));
        reset(storage);
        PERMS.set(List.of());
        assertEquals(403,upload("/upload",TOKEN.get(),valid).statusCode());
        assertEquals(401,upload("/upload",null,valid).statusCode());
        assertEquals(401,send("/upload",TOKEN.get(),"multipart/form-data; boundary="+BOUNDARY,form(valid),true).statusCode());
        MODE.set("unavailable"); assertEquals(503,upload("/api/admin/upload",TOKEN.get(),valid).statusCode());
        assertEquals(500,json(upload("/upload",TOKEN.get(),valid)).path("code").asInt());
        MODE.set("ok");
        AuthenticationHttpTest.STATE.set(null);
        assertEquals(401,upload("/upload",TOKEN.get(),valid).statusCode());
        AuthenticationHttpTest.STATE.set(SessionCodec.encode(new Session(1,AuthenticationHttpTest.SID,ACTOR,"blog","ACTIVE",Instant.now().getEpochSecond()+1800)));
        assertEquals(403,upload("/api/admin/upload",AuthenticationHttpTest.token("blog",600),valid).statusCode());
        verifyNoInteractions(storage); assertTrue(PROTOCOL.get());
    }
    @Test void signedUrlIsNotWrittenWhenParentWebLoggingIsDebug(CapturedOutput output) throws Exception {
        var parent=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        var processor=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor");
        var previous=parent.getLevel();
        parent.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            assertFalse(processor.isDebugEnabled());
            var response=upload("/upload",TOKEN.get(),new Item("img","x.png","image/png",image("png",1,1)));
            assertEquals(200,response.statusCode()); assertEquals(200,json(response).path("code").asInt());
            assertFalse(output.getAll().contains("https://fixture.invalid/signed"));
        } finally { parent.setLevel(previous); }
    }
    @Test void requiresBoundaryAndExactlyOneImgFile() throws Exception {
        Item valid=new Item("img","x.png","image/png",image("png",1,1));
        var noBoundary=send("/upload",TOKEN.get(),"multipart/form-data",form(valid),false);
        assertEquals(400,noBoundary.statusCode()); assertEquals(400,json(noBoundary).path("code").asInt());
        assertEquals(400,upload("/upload",TOKEN.get()).statusCode());
        assertEquals(400,upload("/upload",TOKEN.get(),new Item("other","x.png","image/png",valid.bytes())).statusCode());
        assertEquals(400,upload("/upload",TOKEN.get(),valid,valid).statusCode());
        var tooMany=upload("/upload",TOKEN.get(),valid,valid,valid);
        assertTrue(tooMany.statusCode()==400 || tooMany.statusCode()==413);
        assertEquals(400,send("/upload",TOKEN.get(),"application/octet-stream",valid.bytes(),false).statusCode());
        verifyNoInteractions(storage);
    }
    @Test void rejectsSpoofedOrMalformedImagesAndExcessiveDimensions() throws Exception {
        byte[] png=image("png",1,1), jpeg=image("jpeg",2,2), corrupt=png.clone();
        corrupt[corrupt.length-1]^=1;
        for(byte[] bad:List.of("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(png,png.length-2),corrupt,Arrays.copyOf(jpeg,jpeg.length-2),
                image("png",4097,1),image("png",2049,2049))) {
            var response=upload("/upload",TOKEN.get(),new Item("img","fake.jpg","image/jpeg",bad));
            assertEquals(200,response.statusCode()); assertEquals(507,json(response).path("code").asInt());
        }
        verifyNoInteractions(storage);
    }
    @Test void exactFileLimitAndWholeRequestBudgetAreEnforced() throws Exception {
        byte[] exact=paddedPng(1_048_576);
        var accepted=upload("/upload",TOKEN.get(),new Item("img","exact.png","image/png",exact));
        assertEquals(200,accepted.statusCode()); assertEquals(200,json(accepted).path("code").asInt());
        verify(storage).put(anyString(),eq("image/png"),any(byte[].class));
        reset(storage);
        var totalTooBig=upload("/upload",TOKEN.get(),new Item("img","exact.png","image/png",exact),
                new Item("extra","extra.bin","application/octet-stream",new byte[65_536]));
        assertEquals(413,totalTooBig.statusCode()); assertEquals(413,json(totalTooBig).path("code").asInt());
        verifyNoInteractions(storage);
    }
    @Test void multipartBudgetAndStorageFailureAreSafe() throws Exception {
        var tooBig=upload("/upload",TOKEN.get(),new Item("img","big.png","image/png",new byte[1_048_577]));
        assertEquals(413,tooBig.statusCode()); assertEquals(413,json(tooBig).path("code").asInt());
        verifyNoInteractions(storage);
        when(storage.put(anyString(),anyString(),any(byte[].class))).thenThrow(new IllegalStateException("secret bucket signed-url"));
        var failure=upload("/upload",TOKEN.get(),new Item("img","x.png","image/png",image("png",1,1)));
        assertEquals(503,failure.statusCode()); assertEquals(500,json(failure).path("code").asInt());
        assertFalse(failure.body().contains("secret")); assertFalse(failure.body().contains("bucket"));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.aliyun.oss");
        assertEquals(ch.qos.logback.classic.Level.OFF,logger.getEffectiveLevel());
    }
    private static HttpServer provider() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(EXEC);
            server.createContext("/api/admin/auth/me",e -> {
                try {
                    USER_CALLS.incrementAndGet();
                    if (!"GET".equals(e.getRequestMethod()) || !Objects.equals(TOKEN.get(),e.getRequestHeaders().getFirst("token"))
                            || e.getRequestHeaders().getFirst("Authorization")!=null) PROTOCOL.set(false);
                    if("unavailable".equals(MODE.get())) { e.sendResponseHeaders(503,-1); e.close(); return; }
                    byte[] data=JSON.writeValueAsBytes(Map.of("code",200,"msg","ok","data",
                            Map.of("roles",List.of("admin"),"permissions",PERMS.get(),"user",Map.of("id",ACTOR))));
                    e.sendResponseHeaders(200,data.length); try(var out=e.getResponseBody()) { out.write(data); }
                } catch(Exception ignored) { e.close(); }
            });
            server.start(); return server;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
}
