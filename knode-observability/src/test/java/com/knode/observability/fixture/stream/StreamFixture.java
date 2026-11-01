package com.knode.observability.fixture.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Explicit opt-in test fixture, never part of the production artifact. */
final class StreamFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    record Config(String environment,String run,String stream,String group,String consumer,String host,int port,
                  String username,String password,int database,boolean tls,String mode,String claimStart,URI endpoint,Path dir) {
        static Config from(Map<String,String> env) throws Exception {
            String environment=req(env,"KNODE_FIXTURE_ENVIRONMENT"), run=req(env,"KNODE_FIXTURE_RUN");
            String stream=req(env,"KNODE_FIXTURE_STREAM"), group=req(env,"KNODE_FIXTURE_GROUP");
            String consumer=req(env,"KNODE_FIXTURE_CONSUMER"), host=req(env,"KNODE_FIXTURE_REDIS_HOST");
            String username=req(env,"KNODE_FIXTURE_REDIS_USERNAME"), password=req(env,"KNODE_FIXTURE_REDIS_PASSWORD");
            String mode=req(env,"KNODE_FIXTURE_MODE"), tlsText=req(env,"KNODE_FIXTURE_REDIS_TLS");
            String claimStart=env.getOrDefault("KNODE_FIXTURE_CLAIM_START","0-0");
            int port=Integer.parseInt(req(env,"KNODE_FIXTURE_REDIS_PORT"));
            int database=Integer.parseInt(req(env,"KNODE_FIXTURE_REDIS_DATABASE"));
            URI endpoint=URI.create(req(env,"KNODE_FIXTURE_OTLP_HTTP"));
            if(!environment.matches("verify-[a-z0-9-]{1,24}") || !run.matches("[a-f0-9]{12}") ||
                    !stream.matches("knode:"+environment+":v1:business:[A-Za-z0-9:_-]{1,160}") || !stream.endsWith(":"+run) ||
                    !group.equals("fixture-"+run) || !consumer.matches("fixture-"+run+"-[a-z0-9]{1,16}") ||
                    !username.matches("[A-Za-z0-9_-]{1,100}") || !username.toLowerCase(Locale.ROOT).contains("verify") ||
                    username.equalsIgnoreCase("default") || password.length()<16 || password.length()>512 ||
                    host.length()>253 || host.isBlank() || host.contains("/") || port<1 || port>65535 ||
                    database<0 || database>15 || !Set.of("true","false").contains(tlsText) ||
                    !Set.of("create","crash","reclaim","poll").contains(mode) || !claimStart.matches("[0-9]{1,20}-[0-9]{1,20}") ||
                    !"http".equals(endpoint.getScheme()) || !Set.of("127.0.0.1","localhost","::1","[::1]").contains(endpoint.getHost()) ||
                    endpoint.getPort()<1 || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null ||
                    endpoint.getFragment()!=null || !"/v1/traces".equals(endpoint.getPath())) throw new IllegalArgumentException("Invalid fixture configuration");
            Path root=Path.of("target","stream-fixture").toAbsolutePath().normalize();Path dir=root.resolve(run).normalize();
            if(!dir.getParent().equals(root))throw new IllegalArgumentException("Invalid fixture directory");
            return new Config(environment,run,stream,group,consumer,host,port,username,password,database,
                    Boolean.parseBoolean(tlsText),mode,claimStart,endpoint,dir);
        }
        private static String req(Map<String,String> e,String name){String v=e.get(name);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing "+name);return v;}
    }
    static void run(Config c) throws Exception {
        // No network call can happen before validation and a durable recovery manifest.
        Files.createDirectories(c.dir().getParent());
        if(Files.isSymbolicLink(c.dir().getParent()))throw new IllegalArgumentException("Symlink fixture root");
        Path manifest=c.dir().resolve("manifest.json");
        if(c.mode().equals("create")) {
            Files.createDirectory(c.dir());
            writeManifest(c,manifest,true);
        } else {
            if(!Files.isDirectory(c.dir(),LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest))throw new IllegalArgumentException("Missing fixture run");
            var m=JSON.readTree(Files.readString(manifest));
            if(!c.environment().equals(m.path("environment").asText()) || !c.stream().equals(m.path("stream").asText()) ||
                    !c.group().equals(m.path("group").asText()) || !c.run().equals(m.path("run").asText()) ||
                    !c.host().equals(m.path("host").asText()) || c.port()!=m.path("port").asInt() ||
                    c.database()!=m.path("redisDatabase").asInt() || c.tls()!=m.path("tls").asBoolean() ||
                    !c.username().equals(m.path("username").asText()))
                throw new IllegalArgumentException("Run does not match manifest");
            Path marker=c.dir().resolve("group-created");
            if(Files.isSymbolicLink(marker) || !Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) ||
                    !c.group().equals(Files.readString(marker,StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Fixture group creation not confirmed");
            writeManifest(c,manifest,false);
        }
        var exporter=OtlpHttpSpanExporter.builder().setEndpoint(c.endpoint().toString()).setTimeout(Duration.ofSeconds(2)).build();
        var processor=BatchSpanProcessor.builder(exporter).setMaxQueueSize(128).setMaxExportBatchSize(32)
                .setScheduleDelay(Duration.ofMillis(300)).setExporterTimeout(Duration.ofSeconds(2)).build();
        var provider=SdkTracerProvider.builder().setResource(Resource.getDefault().merge(Resource.create(
                Attributes.builder().put("service.name","knode-stream-observability-fixture").build())))
                .addSpanProcessor(processor).build();
        var telemetry=OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        RedisClient client=null;
        try(FixtureInbox inbox=new FixtureInbox("jdbc:h2:file:"+c.dir().resolve("inbox").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",telemetry)) {
            RedisURI uri=RedisURI.Builder.redis(c.host(),c.port()).withAuthentication(c.username(),c.password())
                    .withDatabase(c.database()).withSsl(c.tls()).withVerifyPeer(SslVerifyMode.FULL)
                    .withTimeout(Duration.ofSeconds(3)).build();
            client=RedisClient.create(uri);
            try(StatefulRedisConnection<String,String> connection=client.connect()) {
                var commands=connection.sync();
                var owner=Consumer.from(c.group(),c.consumer());
                if(c.mode().equals("create")) {
                    commands.xgroupCreate(StreamOffset.from(c.stream(),"0-0"),c.group(),new XGroupCreateArgs().mkstream(true));
                    try(var marker=java.nio.channels.FileChannel.open(c.dir().resolve("group-created"),
                            StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        var contents=StandardCharsets.UTF_8.encode(c.group());
                        while(contents.hasRemaining())marker.write(contents);
                        marker.force(true);
                    }
                } else {
                    List<StreamMessage<String,String>> messages;
                    if(c.mode().equals("reclaim")) {
                        var args=new XAutoClaimArgs<String>().consumer(owner).minIdleTime(Duration.ofSeconds(1)).startId(c.claimStart()).count(10);
                        var claimed=commands.xautoclaim(c.stream(),args);
                        messages=claimed.getMessages();
                        System.out.println("fixture reclaim_next="+claimed.getId());
                    } else messages=commands.xreadgroup(owner,new XReadArgs().count(10),StreamOffset.lastConsumed(c.stream()));
                    for(var message:messages) {
                        FixtureInbox.Result result=inbox.process(message.getId(),message.getBody());
                        System.out.println("fixture result="+result.status()+" attempt="+result.attempt()+" trace="+result.traceId());
                        if(c.mode().equals("crash") && result.acknowledge()) {
                            if(!provider.forceFlush().join(5,java.util.concurrent.TimeUnit.SECONDS).isSuccess())
                                throw new IllegalStateException("Fixture trace export failed; record remains pending");
                            Runtime.getRuntime().halt(73);
                        }
                        if(result.acknowledge())commands.xack(c.stream(),c.group(),message.getId());
                    }
                }
            }
        } finally {if(client!=null)client.shutdown();provider.close();}
    }
    private static void writeManifest(Config c,Path file,boolean initial) throws Exception {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("schemaVersion",1);data.put("environment",c.environment());data.put("run",c.run());
        data.put("stream",c.stream());data.put("group",c.group());data.put("consumer",c.consumer());
        data.put("host",c.host());data.put("port",c.port());data.put("redisDatabase",c.database());data.put("tls",c.tls());
        data.put("username",c.username());data.put("claimStart",c.claimStart());
        data.put("localDb",c.dir().resolve("inbox").toString());data.put("processId",ProcessHandle.current().pid());
        data.put("mode",c.mode());data.put("updatedAt",Instant.now().toString());
        data.put("recovery","Rerun with identical environment, run, stream, group and Redis endpoint; use reclaim after one second, passing the returned reclaim_next as KNODE_FIXTURE_CLAIM_START until 0-0.");
        data.put("cleanup","Only the operator may destroy this exact group or delete the exact test stream after checking producer ownership; remove only this run directory.");
        byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        Path destination=initial ? file : file.resolveSibling("manifest.next.json");
        try(var ch=java.nio.channels.FileChannel.open(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining())ch.write(buffer);
            ch.force(true);
        }
        if(!initial)Files.move(destination,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
