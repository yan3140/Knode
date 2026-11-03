package com.knode.user.config;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real ConfigData processing in a fresh JVM against owned loopback listeners. */
class UserConfigDataIsolationTest {
    @SpringBootConfiguration
    static class Probe {
        public static void main(String[] args) {
            SpringApplication app=new SpringApplication(Probe.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context=app.run(args)) {}
        }
    }

    @Test void localAndProfileImportsCannotReachNacosBeforeBeanStartup() throws Exception {
        for (String source:List.of("application","profile","nested","application-disabled","profile-disabled","nested-disabled")) runProbe(source);
        assertThrows(ClassNotFoundException.class,() -> Class.forName("com.alibaba.cloud.nacos.configdata.NacosConfigDataLocationResolver"));
    }

    @Test void disabledNacosDoesNotConnectAtStartup() throws Exception {
        runProbe("disabled");
    }

    private void runProbe(String source) throws Exception {
        Path target=Path.of("target").toAbsolutePath().normalize();
        Path dir=Files.createTempDirectory(target,"configdata-r1-");
        List<ServerSocket> listeners=new ArrayList<>();
        AtomicBoolean connected=new AtomicBoolean();
        Process process=null;
        try {
            int base;
            for (;;) {
                ServerSocket main=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
                base=main.getLocalPort();
                if (base>64534 || base==8848) {main.close();continue;}
                ServerSocket grpc=null,second=null;
                try {
                    grpc=new ServerSocket(base+1000,8,InetAddress.getByName("127.0.0.1"));
                    second=new ServerSocket(base+1001,8,InetAddress.getByName("127.0.0.1"));
                    listeners.addAll(List.of(main,grpc,second));break;
                } catch (java.io.IOException occupied) {
                    main.close();if (grpc!=null) grpc.close();if (second!=null) second.close();
                }
            }
            for (ServerSocket socket:listeners) {
                Thread watcher=new Thread(() -> {
                    try {while (!socket.isClosed()) {try (Socket ignored=socket.accept()) {connected.set(true);}}}
                    catch (java.io.IOException ignored) {}
                });
                watcher.setDaemon(true);watcher.start();
            }
            String config="spring.cloud.nacos.discovery.enabled=false\n";
            if (!source.equals("disabled")) {
                config+="spring.cloud.nacos.config.enabled="+!source.endsWith("-disabled")+"\nspring.cloud.nacos.config.server-addr=127.0.0.1:"+base
                        +"\nspring.cloud.nacos.config.namespace=verify-r1\n";
                String imported="spring.config.import=optional:nacos:probe.yaml?group=VERIFY_R1\n";
                if (source.startsWith("application")) config+=imported;
                if (source.startsWith("profile")) {
                    config+="spring.profiles.active=attack\n";
                    Files.writeString(dir.resolve("application-attack.properties"),imported);
                }
                if (source.startsWith("nested")) {
                    config+="spring.config.import=optional:"+dir.resolve("nested.properties").toUri()+"\n";
                    Files.writeString(dir.resolve("nested.properties"),imported);
                }
            }
            Files.writeString(dir.resolve("application.properties"),config);
            Path log=dir.resolve("process.log");
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();
            String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            var builder=new ProcessBuilder(java,"-Duser.home="+dir,"-DJM.SNAPSHOT.PATH="+dir.resolve("snapshot"),
                    "-DJM.LOG.PATH="+dir.resolve("logs"),"-Dnacos.cache.data.init.snapshot=false",
                    "-cp",classpath,Probe.class.getName(),"--spring.config.additional-location="+dir.toUri(),"--spring.main.banner-mode=off");
            builder.environment().keySet().removeIf(k -> k.startsWith("KNODE_") || k.startsWith("NACOS_")
                    || k.startsWith("SPRING_") || k.startsWith("USER_") || k.startsWith("OTEL_")
                    || k.equals("JAVA_TOOL_OPTIONS") || k.equals("JDK_JAVA_OPTIONS") || k.equals("_JAVA_OPTIONS"));
            builder.redirectErrorStream(true);builder.redirectOutput(log.toFile());
            process=builder.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
            while (process.isAlive() && !connected.get() && System.nanoTime()<deadline) Thread.sleep(50);
            boolean completed=!process.isAlive();
            if (!completed) {
                process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            }
            String output=Files.readString(log);
            assertFalse(connected.get(),source+" config made an unexpected Nacos connection");
            assertTrue(completed,source+" config did not finish startup");
            if (source.equals("disabled")) assertEquals(0,process.exitValue(),output);
            else {
                assertNotEquals(0,process.exitValue(),source+" config should be rejected");
                assertTrue(output.toLowerCase(Locale.ROOT).contains("nacos"),output);
            }
        } finally {
            if (process!=null && process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            for (ServerSocket socket:listeners) socket.close();
            try (var paths=Files.walk(dir)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    for (int attempt=0;;attempt++) {
                        try {Files.deleteIfExists(path);break;}
                        catch (java.nio.file.FileSystemException busy) {
                            if (attempt==20) throw busy;
                            Thread.sleep(100);
                        }
                    }
                }
            }
        }
    }
}
