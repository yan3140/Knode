package com.knode.content.relay;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

class RedisRelayFakeTest {
    static Process server;
    static int port;
    static final String PYTHON="""
            import sys
            sys.path.insert(0,sys.argv[1])
            import fakeredis
            server=fakeredis.TcpFakeServer(("127.0.0.1",0))
            print(server.server_address[1],flush=True)
            server.serve_forever()
            """;
    @BeforeAll static void start() throws Exception {
        Path deps=Path.of("node_modules","counter-fake-python").toAbsolutePath();
        if(!Files.isDirectory(deps.resolve("fakeredis"))) deps=Path.of("knode-content","node_modules","counter-fake-python").toAbsolutePath();
        Assumptions.assumeTrue(Files.isDirectory(deps.resolve("fakeredis")),"Install isolated fakeredis fixture");
        String code=Base64.getEncoder().encodeToString(PYTHON.getBytes(StandardCharsets.UTF_8));
        String launcher="import base64;exec(base64.b64decode('"+code+"'))";
        server=new ProcessBuilder("python","-u","-X","utf8","-c",launcher,deps.toString()).redirectErrorStream(true).start();
        BufferedReader reader=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
        String first=CompletableFuture.supplyAsync(() -> {try{return reader.readLine();}catch(Exception e){return null;}}).get(15,TimeUnit.SECONDS);
        assertNotNull(first);port=Integer.parseInt(first.trim());
    }
    @AfterAll static void stop() throws Exception {
        if(server!=null) {server.destroyForcibly();server.waitFor(5,TimeUnit.SECONDS);}
    }
    @Test void xaddContainsOneExactEventFieldAndNeverTrims() {
        var redisConfig=new RedisStandaloneConfiguration("127.0.0.1",port);
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5))
                .clientOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).autoReconnect(false).build()).build();
        var factory=new LettuceConnectionFactory(redisConfig,client);factory.setShareNativeConnection(false);factory.afterPropertiesSet();factory.start();
        try {
            var template=new StringRedisTemplate(factory);template.afterPropertiesSet();
            String key="knode:verify-local:v1:events:content";
            var transport=new RedisRelayStream(template,factory,key);
            String raw="{\"sample\":\""+ "中".repeat(100_000)+"\"}";
            String first=transport.publish(raw),second=transport.publish(raw);
            assertNotEquals(first,second);
            List<ByteRecord> records=template.execute((RedisCallback<List<ByteRecord>>) connection ->
                    connection.streamCommands().xRange(key.getBytes(StandardCharsets.UTF_8),Range.unbounded(),
                            org.springframework.data.redis.connection.Limit.limit().count(10)));
            assertEquals(2,records.size());
            for(ByteRecord record:records) {
                Map<byte[],byte[]> values=record.getValue();assertEquals(1,values.size());
                var entry=values.entrySet().iterator().next();
                assertEquals("event",new String(entry.getKey(),StandardCharsets.UTF_8));
                assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8),entry.getValue());
            }
        } finally {factory.destroy();}
    }
}
