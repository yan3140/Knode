package com.knode.gateway;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** A separate client JVM trusts only this temporary CA. No global trust settings change. */
class GatewayAuthRedisTrustedTlsTest {
    @TempDir Path dir;

    @Test void trustedCertificateAllowsGetAndWrongHostFails() throws Exception {
        String pass = UUID.randomUUID().toString();
        Path ca=dir.resolve("ca.p12"), server=dir.resolve("server.p12"), csr=dir.resolve("s.csr");
        Path cert=dir.resolve("s.crt"), cacert=dir.resolve("ca.crt"), trust=dir.resolve("trust.p12");
        keytool("-genkeypair","-alias","ca","-dname","CN=Fixture CA","-keyalg","RSA",
                "-ext","bc=ca:true","-validity","1","-keystore",ca.toString(),"-storepass",pass);
        keytool("-exportcert","-alias","ca","-keystore",ca.toString(),"-storepass",pass,"-file",cacert.toString());
        keytool("-genkeypair","-alias","server","-dname","CN=localhost","-keyalg","RSA",
                "-validity","1","-keystore",server.toString(),"-storepass",pass);
        keytool("-certreq","-alias","server","-keystore",server.toString(),"-storepass",pass,"-file",csr.toString());
        keytool("-gencert","-alias","ca","-keystore",ca.toString(),"-storepass",pass,"-infile",csr.toString(),
                "-outfile",cert.toString(),"-validity","1","-ext","SAN=dns:localhost","-ext","EKU=serverAuth");
        keytool("-importcert","-alias","ca","-keystore",server.toString(),"-storepass",pass,"-file",cacert.toString());
        keytool("-importcert","-alias","server","-keystore",server.toString(),"-storepass",pass,"-file",cert.toString());
        keytool("-importcert","-alias","ca","-keystore",trust.toString(),"-storepass",pass,"-file",cacert.toString());
        try (Peer peer = new Peer(server, pass)) {
            assertThat(client(trust,pass,"localhost",peer.port(),"success")).isZero();
            assertThat(peer.gets.get()).isEqualTo(1);
            assertThat(client(trust,pass,"127.0.0.1",peer.port(),"hostname-failure")).isZero();
            assertThat(peer.gets.get()).isEqualTo(1);
        }
    }
    static String binary(String name) { return Path.of(System.getProperty("java.home"),"bin",
            name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString(); }
    static void keytool(String... args) throws Exception {
        var command=new ArrayList<String>(); command.add(binary("keytool")); command.addAll(List.of(args));
        command.addAll(List.of("-storetype","PKCS12","-noprompt"));
        Process p=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!p.waitFor(30,TimeUnit.SECONDS)) {p.destroyForcibly(); throw new IllegalStateException("keytool timeout");}
        if(p.exitValue()!=0) throw new IllegalStateException("temporary certificate generation failed");
    }
    static int client(Path trust,String pass,String host,int port,String expected) throws Exception {
        Process p=new ProcessBuilder(binary("java"),"-Djavax.net.ssl.trustStore="+trust,
                "-Djavax.net.ssl.trustStorePassword="+pass,"-Djavax.net.ssl.trustStoreType=PKCS12",
                "-cp",System.getProperty("surefire.test.class.path"),Client.class.getName(),
                host,Integer.toString(port),expected).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!p.waitFor(25,TimeUnit.SECONDS)){p.destroyForcibly(); throw new IllegalStateException("client timeout");}
        return p.exitValue();
    }
    public static class Client {
        public static void main(String[] args) {
            var config=new AuthenticationConfiguration();
            var factory=config.authRedisConnection(args[0],Integer.parseInt(args[1]),"fixture-reader","fixture-secret",0,2000,true);
            factory.afterPropertiesSet(); factory.start();
            try {
                try {
                    String value=config.sessionReader(factory,"verify-local").get("sid-test").block(Duration.ofSeconds(8));
                    if(!args[2].equals("success") || !"sample-session".equals(value)) System.exit(2);
                } catch(Exception failure) {
                    StringBuilder error=new StringBuilder();
                    for(Throwable t=failure;t!=null;t=t.getCause()) error.append(t.getClass().getName()).append(":").append(t.getMessage()).append("\n");
                    String detail=error.toString();
                    if(!args[2].equals("hostname-failure") || !detail.contains("SSLHandshakeException")
                            || !(detail.contains("subject alternative") || detail.contains("No name matching"))) System.exit(3);
                }
            } finally { factory.destroy(); }
        }
    }
    static final class Peer implements AutoCloseable {
        final SSLServerSocket server; final Thread thread; final AtomicInteger gets=new AtomicInteger();
        Peer(Path store,String pass) throws Exception {
            KeyStore keys=KeyStore.getInstance("PKCS12");
            try(InputStream in=Files.newInputStream(store)){keys.load(in,pass.toCharArray());}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys,pass.toCharArray()); SSLContext context=SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),null,null);
            server=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(this::serve,"trusted-tls-resp-fixture"); thread.setDaemon(true); thread.start();
        }
        int port(){return server.getLocalPort();}
        void serve(){
            while(!server.isClosed()){
                try(SSLSocket socket=(SSLSocket)server.accept()){
                    socket.setSoTimeout(5000); socket.startHandshake();
                    InputStream in=new BufferedInputStream(socket.getInputStream()); OutputStream out=socket.getOutputStream();
                    while(!socket.isClosed()){
                        String head=RedisSessionReaderTest.RespPeer.line(in);
                        if(head==null) break;
                        if(!head.startsWith("*")) throw new IllegalStateException("bad RESP");
                        var args=new ArrayList<String>();
                        for(int i=0;i<Integer.parseInt(head.substring(1));i++){
                            String size=RedisSessionReaderTest.RespPeer.line(in);
                            if(size==null || !size.startsWith("$")) throw new IllegalStateException("bad bulk");
                            int len=Integer.parseInt(size.substring(1)); byte[] bytes=in.readNBytes(len);
                            if(bytes.length!=len || in.read()!=13 || in.read()!=10) throw new IllegalStateException("bad length");
                            args.add(new String(bytes,StandardCharsets.UTF_8));
                        }
                        String cmd=args.get(0).toUpperCase(Locale.ROOT);
                        String reply;
                        if(cmd.equals("HELLO")) reply="-ERR unknown command HELLO\r\n";
                        else if(cmd.equals("GET")){gets.incrementAndGet(); reply="$14\r\nsample-session\r\n";}
                        else if(cmd.equals("PING")) reply="+PONG\r\n";
                        else reply="+OK\r\n";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));out.flush();
                    }
                } catch(Exception ignored){if(server.isClosed()) return;}
            }
        }
        public void close() throws Exception {server.close();thread.join(2000);}
    }
}
