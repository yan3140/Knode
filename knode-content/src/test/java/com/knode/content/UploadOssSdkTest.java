package com.knode.content;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.knode.content.upload.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class UploadOssSdkTest {
    HttpServer server;
    ExecutorService executor;
    AtomicInteger hits;
    AtomicReference<byte[]> body;
    AtomicReference<String> path,contentType,disposition,authorization;
    AtomicReference<String> mode;

    @BeforeEach void start() throws Exception {
        hits=new AtomicInteger(); body=new AtomicReference<>(); path=new AtomicReference<>();
        contentType=new AtomicReference<>(); disposition=new AtomicReference<>(); authorization=new AtomicReference<>();
        mode=new AtomicReference<>("ok");
        executor=Executors.newCachedThreadPool(r -> { Thread t=new Thread(r); t.setDaemon(true); return t; });
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
        server.createContext("/",e -> {
            try {
                hits.incrementAndGet(); path.set(e.getRequestURI().getPath());
                contentType.set(e.getRequestHeaders().getFirst("Content-Type"));
                disposition.set(e.getRequestHeaders().getFirst("Content-Disposition"));
                authorization.set(e.getRequestHeaders().getFirst("Authorization"));
                body.set(e.getRequestBody().readAllBytes());
                if("slow".equals(mode.get())) Thread.sleep(1500);
                if("redirect".equals(mode.get())) {
                    e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unexpected");
                    e.sendResponseHeaders(302,-1); e.close(); return;
                }
                if("failure".equals(mode.get())) {
                    byte[] error="<Error><Code>AccessDenied</Code><Message>synthetic private detail</Message></Error>".getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(403,error.length); try(var out=e.getResponseBody()) { out.write(error); } return;
                }
                e.getResponseHeaders().set("ETag","\"fixture\"");
                e.getResponseHeaders().set("x-oss-request-id","fixture-request");
                e.sendResponseHeaders(200,-1); e.close();
            } catch(Exception ignored) { e.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    AliyunUploadStorage storage(int budget) {
        var config=UploadConfiguration.sdkConfiguration();
        config.setSLDEnabled(true);
        config.setRequestTimeout(budget);
        OSS client=OSSClientBuilder.create().endpoint("http://127.0.0.1:"+server.getAddress().getPort())
                .credentialsProvider(new DefaultCredentialProvider("synthetic-id","synthetic-secret"))
                .clientConfiguration(config).region("cn-test").build();
        return new AliyunUploadStorage(client,"fixture-bucket");
    }
    @Test void actualSdkSendsBoundedPayloadMetadataAndProducesSignedUrl() {
        byte[] data={1,2,3,4};
        try(var store=storage(2000)) {
            String url=store.put("2026/11/11/0123456789abcdef0123456789abcdef.png","image/png",data);
            assertEquals(1,hits.get()); assertArrayEquals(data,body.get());
            assertEquals("/fixture-bucket/2026/11/11/0123456789abcdef0123456789abcdef.png",path.get());
            assertEquals("image/png",contentType.get()); assertEquals("inline",disposition.get());
            assertNotNull(authorization.get()); assertTrue(authorization.get().contains("OSS4-HMAC-SHA256"));
            URI parsed=URI.create(url); assertEquals("127.0.0.1",parsed.getHost());
            String query=parsed.getRawQuery().toLowerCase(java.util.Locale.ROOT);
            assertTrue(query.contains("x-oss-signature=")); assertTrue(query.contains("x-oss-expires="));
            String expiry=java.util.Arrays.stream(query.split("&")).filter(x -> x.startsWith("x-oss-expires="))
                    .findFirst().orElseThrow().split("=",2)[1];
            int seconds=Integer.parseInt(expiry);
            assertTrue(seconds>=3598 && seconds<=3600);
        }
    }
    @Test void productionStyleSignedUrlUsesHttpsWithoutMakingANetworkCall() {
        var config=UploadConfiguration.sdkConfiguration();
        OSS client=OSSClientBuilder.create().endpoint("https://oss-cn-test.aliyuncs.com")
                .credentialsProvider(new DefaultCredentialProvider("synthetic-id","synthetic-secret"))
                .clientConfiguration(config).region("cn-test").build();
        try {
            var request=new com.aliyun.oss.model.GeneratePresignedUrlRequest("fixture-bucket","2026/11/11/test.png");
            request.setExpiration(java.util.Date.from(java.time.Instant.now().plusSeconds(3600)));
            URI uri=URI.create(client.generatePresignedUrl(request).toExternalForm());
            assertEquals("https",uri.getScheme());
            assertEquals("fixture-bucket.oss-cn-test.aliyuncs.com",uri.getHost());
            assertEquals(0,hits.get());
        } finally { client.shutdown(); }
    }
    @Test void sdkDoesNotRetryOrFollowRedirectAndMapsErrorsSafely() {
        mode.set("redirect");
        try(var store=storage(1500)) {
            UploadFailure failure=assertThrows(UploadFailure.class,() -> store.put("2026/11/11/a.png","image/png",new byte[]{1}));
            assertEquals(503,failure.status()); assertEquals(500,failure.code());
            assertNull(failure.getMessage()); assertEquals(1,hits.get()); assertFalse(path.get().contains("unexpected"));
        }
        mode.set("failure"); hits.set(0);
        try(var store=storage(1500)) {
            UploadFailure failure=assertThrows(UploadFailure.class,() -> store.put("2026/11/11/b.jpg","image/jpeg",new byte[]{1}));
            assertNull(failure.getMessage()); assertEquals(1,hits.get());
        }
    }
    @Test void sdkRequestHasATotalTimeout() {
        mode.set("slow");
        try(var store=storage(300)) {
            long before=System.nanoTime();
            UploadFailure failure=assertThrows(UploadFailure.class,() -> store.put("2026/11/11/a.png","image/png",new byte[]{1}));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before);
            assertEquals(503,failure.status()); assertTrue(elapsed<2500,"request exceeded bounded test time");
            assertEquals(1,hits.get());
        }
    }
    @Test void concurrentSdkCallsAreBoundedAndClientIsClosed() throws Exception {
        var client=org.mockito.Mockito.mock(OSS.class);
        var entered=new CountDownLatch(10); var release=new CountDownLatch(1);
        org.mockito.Mockito.when(client.putObject(org.mockito.ArgumentMatchers.any(com.aliyun.oss.model.PutObjectRequest.class)))
                .thenAnswer(call -> { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return new com.aliyun.oss.model.PutObjectResult(); });
        org.mockito.Mockito.when(client.generatePresignedUrl(org.mockito.ArgumentMatchers.any(com.aliyun.oss.model.GeneratePresignedUrlRequest.class)))
                .thenReturn(URI.create("https://fixture.invalid/signed").toURL());
        var pool=Executors.newFixedThreadPool(10);
        try(var store=new AliyunUploadStorage(client,"fixture-bucket")) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<10;i++) futures.add(pool.submit(() -> store.put("x","image/png",new byte[]{1})));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            UploadFailure failure=assertThrows(UploadFailure.class,() -> store.put("x","image/png",new byte[]{1}));
            assertEquals(503,failure.status());
            release.countDown(); for(Future<?> future:futures) future.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        org.mockito.Mockito.verify(client).shutdown();
    }
    @Test void outerDeadlineStillAppliesWhenSdkCallIgnoresInterruption() throws Exception {
        var client=org.mockito.Mockito.mock(OSS.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        org.mockito.Mockito.when(client.putObject(org.mockito.ArgumentMatchers.any(com.aliyun.oss.model.PutObjectRequest.class)))
                .thenAnswer(call -> {
                    entered.countDown();
                    while (true) {
                        try { if (release.await(1,TimeUnit.SECONDS)) break; }
                        catch (InterruptedException ignored) { }
                    }
                    return new com.aliyun.oss.model.PutObjectResult();
                });
        var store=new AliyunUploadStorage(client,"fixture-bucket");
        try {
            long before=System.nanoTime();
            UploadFailure failure=assertThrows(UploadFailure.class,() -> store.put("x","image/png",new byte[]{1}));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before);
            assertEquals(0,entered.getCount()); assertEquals(503,failure.status());
            assertTrue(elapsed>=4500 && elapsed<7000,"outer deadline did not bound the call");
        } finally { release.countDown(); store.close(); }
    }
    @Test void disabledStartsWithoutCredentialsAndInvalidEnabledSettingsFailClosed() {
        new ApplicationContextRunner().withUserConfiguration(UploadConfiguration.class)
                .withPropertyValues("KNODE_CONTENT_UPLOAD_ENABLED=false").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(UploadSettings.class).enabled());
                    UploadFailure failure=assertThrows(UploadFailure.class,() -> context.getBean(UploadStorage.class).put("x","image/png",new byte[0]));
                    assertEquals(503,failure.status()); assertEquals(500,failure.code());
                });
        for(String endpoint:new String[]{"http://127.0.0.1","https://evil.example","https://user@oss-cn-test.aliyuncs.com","https://oss-cn-test.aliyuncs.com:443"}) {
            assertThrows(IllegalStateException.class,() -> UploadSettings.parse("true",endpoint,"cn-test","fixture-bucket"));
        }
        assertThrows(IllegalStateException.class,() -> UploadSettings.parse("true","https://oss-cn-test.aliyuncs.com","cn-test",""));
        assertThrows(IllegalStateException.class,() -> UploadSettings.parse("maybe","","",""));
        new ApplicationContextRunner().withUserConfiguration(UploadConfiguration.class).withPropertyValues(
                "KNODE_CONTENT_UPLOAD_ENABLED=true","KNODE_CONTENT_UPLOAD_ENDPOINT=https://oss-cn-test.aliyuncs.com",
                "KNODE_CONTENT_UPLOAD_REGION=cn-test").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
