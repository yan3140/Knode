package com.knode.user;

import com.alibaba.nacos.api.*;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.knode.user.config.ControlledRelaySettings;
import java.net.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only, against an externally created new loopback Nacos and disposable namespaces. */
@EnabledIfSystemProperty(named="knode.it.nacos.enabled",matches="true")
class UserNacosIT {
    private static String required(String key) {
        String value=System.getenv(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+key);
        return value;
    }
    private static int port() throws Exception {
        try (var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {return socket.getLocalPort();}
    }
    private static Properties properties(String address,String namespace) {
        var p=new Properties();p.put(PropertyKeyConst.SERVER_ADDR,address);p.put(PropertyKeyConst.NAMESPACE,namespace);return p;
    }
    private static void await(java.util.concurrent.Callable<Boolean> condition,String detail) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime()<deadline) {if (condition.call()) return;Thread.sleep(250);}
        fail(detail);
    }
    private static ControlledRelaySettings settings(String addr,String namespace,String group) throws Exception {
        var e=new MockEnvironment().withProperty("USER_NACOS_CONFIG_ENABLED","true").withProperty("USER_NACOS_SERVER_ADDR",addr)
                .withProperty("USER_NACOS_NAMESPACE",namespace).withProperty("USER_NACOS_CONFIG_GROUP",group);
        var settings=new ControlledRelaySettings(e);settings.run(new DefaultApplicationArguments(new String[0]));return settings;
    }
    @Test void actualJarRegistersAndControlledConfigurationRefreshesInIsolatedNamespaces() throws Exception {
        String address=required("KNODE_IT_NACOS_ADDR"),ns1=required("KNODE_IT_NACOS_NS1"),ns2=required("KNODE_IT_NACOS_NS2"),group=required("KNODE_IT_NACOS_GROUP");
        URI target=URI.create("http://"+address);
        if (!"127.0.0.1".equals(target.getHost()) || target.getPort()<1024 || target.getPort()==8848 || target.getPort()>64534
                || target.getUserInfo()!=null || target.getPath()==null || !target.getPath().isEmpty()
                || !ns1.matches("verify-[a-z0-9-]{1,25}") || !ns2.matches("verify-[a-z0-9-]{1,25}") || ns1.equals(ns2)
                || !group.matches("VERIFY_[A-Z0-9_]{1,40}")) throw new IllegalArgumentException("Explicit isolated Nacos inputs required");
        Path jar=Path.of(System.getProperty("knode.it.user.jar","target/knode-user-1.0-SNAPSHOT.jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("Built User JAR required");
        String cache=required("KNODE_IT_NACOS_CLIENT_CACHE");
        Path cachePath=Path.of(cache).toAbsolutePath().normalize();
        if (!cachePath.startsWith(Path.of("target").toAbsolutePath().normalize()) || !Files.isDirectory(cachePath))
            throw new IllegalArgumentException("Dedicated target cache directory required");
        System.setProperty("JM.SNAPSHOT.PATH",cachePath.toString());
        System.setProperty("JM.LOG.PATH",cachePath.resolve("logs").toString());
        System.setProperty("nacos.cache.data.init.snapshot","false");
        var config1=NacosFactory.createConfigService(properties(address,ns1));
        var config2=NacosFactory.createConfigService(properties(address,ns2));
        var naming1=NacosFactory.createNamingService(properties(address,ns1));
        var naming2=NacosFactory.createNamingService(properties(address,ns2));
        ControlledRelaySettings first=null,second=null,restarted=null;
        Process process=null;
        Path keyDir=Files.createTempDirectory(Path.of("target"),"g8-nacos-keys-");
        Path privateKey=keyDir.resolve("private.pem"),publicKey=keyDir.resolve("public.pem");
        try {
            assertTrue(config1.publishConfig("knode-user.relay.json",group,"{\"sessionEventRelayBatchSize\":3}"));
            assertTrue(config2.publishConfig("knode-user.relay.json",group,"{\"sessionEventRelayBatchSize\":19}"));
            first=settings(address,ns1,group);second=settings(address,ns2,group);
            var a=first;var b=second;
            await(() -> a.batchSize()==3 && b.batchSize()==19 && Status.UP.equals(a.health().getStatus()),"Initial namespace config not loaded");
            assertTrue(config1.publishConfig("knode-user.relay.json",group,"{\"sessionEventRelayBatchSize\":7}"));
            await(() -> a.batchSize()==7,"Controlled refresh not observed");
            assertEquals(19,b.batchSize());
            assertTrue(config1.publishConfig("knode-user.relay.json",group,"{\"sessionEventRelayBatchSize\":0}"));
            await(() -> !Status.UP.equals(a.health().getStatus()),"Invalid config did not affect readiness");
            assertEquals(7,a.batchSize());
            assertTrue(config1.publishConfig("knode-user.relay.json",group,"{\"sessionEventRelayBatchSize\":8}"));
            await(() -> a.batchSize()==8 && Status.UP.equals(a.health().getStatus()),"Recovery not observed");
            first.close();first=null;restarted=settings(address,ns1,group);var c=restarted;
            await(() -> c.batchSize()==8,"Restart did not load durable config");
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            Files.writeString(privateKey,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
            Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
            int userPort=port(),management=port(),deadDb=port(),deadRedis=port();
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
            var builder=new ProcessBuilder(java,"-DJM.SNAPSHOT.PATH="+cachePath.resolve("app"),"-DJM.LOG.PATH="+cachePath.resolve("app-logs"),"-Dnacos.cache.data.init.snapshot=false","-jar",jar.toString(),"--server.address=127.0.0.1","--spring.cloud.nacos.discovery.ip=127.0.0.1");
            var e=builder.environment();e.keySet().removeIf(k -> k.startsWith("KNODE_") || k.startsWith("NACOS_") || k.startsWith("SPRING_") || k.startsWith("USER_"));
            e.put("USER_PORT",Integer.toString(userPort));e.put("USER_MANAGEMENT_PORT",Integer.toString(management));
            e.put("KNODE_USER_DB_URL","jdbc:mysql://127.0.0.1:"+deadDb+"/g8_fixture");e.put("KNODE_USER_DB_USERNAME","fixture");e.put("KNODE_USER_DB_PASSWORD","fixture");
            e.put("KNODE_AUTH_ENVIRONMENT","verify-nacos");e.put("KNODE_AUTH_SIGNING_KID","test-key");
            e.put("KNODE_AUTH_SIGNING_PRIVATE_FILE",privateKey.toAbsolutePath().toString());e.put("KNODE_AUTH_SIGNING_PUBLIC_FILE",publicKey.toAbsolutePath().toString());
            for (String prefix:List.of("KNODE_AUTH_REDIS_READ_","KNODE_AUTH_REDIS_WRITE_")) {
                e.put(prefix+"HOST","127.0.0.1");e.put(prefix+"PORT",Integer.toString(deadRedis));e.put(prefix+"DATABASE","0");
                e.put(prefix+"USERNAME",prefix.contains("READ")?"fixture-read":"fixture-write");e.put(prefix+"PASSWORD","fixture-password");e.put(prefix+"TLS","false");
            }
            e.put("KNODE_AUTH_REVOCATION_INITIAL_MS","600000");e.put("KNODE_SESSION_EVENTS_INITIAL_MS","600000");
            e.put("USER_NACOS_DISCOVERY_ENABLED","true");e.put("USER_NACOS_CONFIG_ENABLED","true");e.put("USER_NACOS_SERVER_ADDR",address);
            e.put("USER_NACOS_NAMESPACE",ns1);e.put("USER_NACOS_GROUP",group);e.put("USER_NACOS_CONFIG_GROUP",group);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process=builder.start();var launched=process;
            await(() -> {
                if (!launched.isAlive()) fail("User JAR exited before registration");
                return naming1.getAllInstances("knode-user",group).stream().anyMatch(i -> i.getPort()==userPort);
            },"User JAR did not register");
            assertTrue(naming2.getAllInstances("knode-user",group).stream().noneMatch(i -> i.getPort()==userPort));
            process.destroy();if (!process.waitFor(20,TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            await(() -> naming1.getAllInstances("knode-user",group).stream().noneMatch(i -> i.getPort()==userPort),"User JAR did not deregister");
            process=builder.start();var afterRestart=process;
            await(() -> {
                if (!afterRestart.isAlive()) fail("User JAR exited after restart");
                return naming1.getAllInstances("knode-user",group).stream().anyMatch(i -> i.getPort()==userPort);
            },"User JAR did not re-register after restart");
            process.destroy();if (!process.waitFor(20,TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            await(() -> naming1.getAllInstances("knode-user",group).stream().noneMatch(i -> i.getPort()==userPort),"Restarted User JAR did not deregister");
        } finally {
            if (process!=null && process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            if (first!=null) first.close();if (second!=null) second.close();if (restarted!=null) restarted.close();
            config1.shutDown();config2.shutDown();naming1.shutDown();naming2.shutDown();
            Files.deleteIfExists(privateKey);Files.deleteIfExists(publicKey);Files.deleteIfExists(keyDir);
        }
    }
}
