package com.knode.observability.fixture.stream;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real Lettuce commands against a loopback RESP substitute, not real Redis. */
class StreamFixtureProcessTest {
    public static class Launcher {
        public static void main(String[] args) throws Exception { StreamFixture.run(StreamFixture.Config.from(System.getenv())); }
    }
    @Test void processHaltBeforeAckAndRestartReclaimsOnlyOneEffect() throws Exception {
        String run=UUID.randomUUID().toString().replace("-","").substring(0,12);
        Path dir=Path.of("target","stream-fixture",run).toAbsolutePath();
        HttpServer collector=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        collector.createContext("/v1/traces",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);exchange.close();});
        collector.start();
        try(Peer peer=new Peer("knode:verify-local:v1:business:test:"+run)) {
            Map<String,String> env=new HashMap<>();
            env.put("KNODE_FIXTURE_ENVIRONMENT","verify-local");env.put("KNODE_FIXTURE_RUN",run);
            env.put("KNODE_FIXTURE_STREAM",peer.stream);env.put("KNODE_FIXTURE_GROUP","fixture-"+run);
            env.put("KNODE_FIXTURE_CONSUMER","fixture-"+run+"-first");
            env.put("KNODE_FIXTURE_REDIS_HOST","127.0.0.1");env.put("KNODE_FIXTURE_REDIS_PORT",Integer.toString(peer.port()));
            env.put("KNODE_FIXTURE_REDIS_DATABASE","0");env.put("KNODE_FIXTURE_REDIS_USERNAME","fixture_verify_user");
            env.put("KNODE_FIXTURE_REDIS_PASSWORD","synthetic-fixture-password");env.put("KNODE_FIXTURE_REDIS_TLS","false");
            env.put("KNODE_FIXTURE_OTLP_HTTP","http://127.0.0.1:"+collector.getAddress().getPort()+"/v1/traces");
            assertThat(run(env,"create")).isZero();
            assertThat(run(env,"crash")).isEqualTo(73);
            assertThat(peer.acks.get()).isZero();
            env.put("KNODE_FIXTURE_CONSUMER","fixture-"+run+"-restart");
            assertThat(run(env,"reclaim")).isZero();
            assertThat(peer.acks.get()).isEqualTo(1);
            assertThat(peer.commands).contains("XGROUP","XREADGROUP","XAUTOCLAIM","XACK");
            String group="fixture-"+run;
            assertThat(peer.calls.contains(List.of("XGROUP","CREATE",peer.stream,group,"0-0","MKSTREAM"))).isTrue();
            assertThat(peer.calls.contains(List.of("XREADGROUP","GROUP",group,"fixture-"+run+"-first","COUNT","10","STREAMS",peer.stream,">"))).isTrue();
            assertThat(peer.calls.contains(List.of("XAUTOCLAIM",peer.stream,group,"fixture-"+run+"-restart","1000","0-0","COUNT","10"))).isTrue();
            assertThat(peer.calls.contains(List.of("XACK",peer.stream,group,"1-0"))).isTrue();
            try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+dir.resolve("inbox").toString().replace('\\','/')+";WRITE_DELAY=0");
                Statement s=c.createStatement()) {
                try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM inbox")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
                try(ResultSet r=s.executeQuery("SELECT attempts,status FROM delivery")) {
                    assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(2);
                    assertThat(r.getString(2)).isEqualTo("DUPLICATE");assertThat(r.next()).isFalse();
                }
            }
            String manifest=Files.readString(dir.resolve("manifest.json"));
            assertThat(manifest).contains(peer.stream,"fixture-"+run,"restart").doesNotContain("synthetic-fixture-password");
        } finally {
            collector.stop(0);
            if(Files.exists(dir))try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }
    static int run(Map<String,String> env,String mode) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        ProcessBuilder builder=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path"),Launcher.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(env);builder.environment().put("KNODE_FIXTURE_MODE",mode);
        Process p=builder.start();if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("fixture timeout");}
        return p.exitValue();
    }
    static final class Peer implements AutoCloseable {
        final ServerSocket server;final Thread thread;final String stream;
        final AtomicInteger acks=new AtomicInteger();final List<String> commands=new CopyOnWriteArrayList<>();
        final List<List<String>> calls=new CopyOnWriteArrayList<>();
        volatile boolean delivered;
        Peer(String stream) throws Exception {
            this.stream=stream;server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(this::serve,"stream-resp-substitute");thread.setDaemon(true);thread.start();
        }
        int port(){return server.getLocalPort();}
        void serve(){
            while(!server.isClosed()){
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(10000);InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
                    while(!socket.isClosed()){
                        String head=line(in);if(head==null)break;
                        if(!head.startsWith("*"))throw new IllegalStateException();
                        List<String> args=new ArrayList<>();
                        for(int i=0;i<Integer.parseInt(head.substring(1));i++){
                            String size=line(in);if(size==null || !size.startsWith("$"))throw new IllegalStateException();
                            int n=Integer.parseInt(size.substring(1));byte[] b=in.readNBytes(n);
                            if(b.length!=n||in.read()!=13||in.read()!=10)throw new IllegalStateException();
                            args.add(new String(b,StandardCharsets.UTF_8));
                        }
                        String cmd=args.get(0).toUpperCase(Locale.ROOT);commands.add(cmd);
                        if(Set.of("XGROUP","XREADGROUP","XAUTOCLAIM","XACK").contains(cmd))calls.add(List.copyOf(args));
                        String response;
                        if(cmd.equals("HELLO"))response="-ERR unknown command\r\n";
                        else if(cmd.equals("XREADGROUP")){delivered=true;response=array(array(bulk(stream)+array(message())));}
                        else if(cmd.equals("XAUTOCLAIM"))response=array(bulk("0-0")+array(delivered?message():"")+array(""));
                        else if(cmd.equals("XACK")){acks.incrementAndGet();response=":1\r\n";}
                        else if(cmd.equals("PING"))response="+PONG\r\n";
                        else response="+OK\r\n";
                        out.write(response.getBytes(StandardCharsets.UTF_8));out.flush();
                    }
                } catch(Exception ignored){if(server.isClosed())return;}
            }
        }
        String message(){return array(bulk("1-0")+array(bulk("event")+bulk(FixtureInboxTest.event(1,"LOGOUT",null))));}
        static String bulk(String s){return "$"+s.getBytes(StandardCharsets.UTF_8).length+"\r\n"+s+"\r\n";}
        static String array(String s){
            int count=0;int i=0;
            while(i<s.length()){
                count++;char t=s.charAt(i);int end=s.indexOf("\r\n",i);int n=Integer.parseInt(s.substring(i+1,end));
                if(t=='$')i=end+2+n+2;
                else if(t=='*'){i=skipArray(s,i);}else throw new IllegalStateException();
            }
            return "*"+count+"\r\n"+s;
        }
        static int skipArray(String s,int start){
            int end=s.indexOf("\r\n",start), n=Integer.parseInt(s.substring(start+1,end)),i=end+2;
            for(int k=0;k<n;k++){char t=s.charAt(i);int e=s.indexOf("\r\n",i);int v=Integer.parseInt(s.substring(i+1,e));i=t=='$'?e+2+v+2:skipArray(s,i);}
            return i;
        }
        static String line(InputStream in) throws Exception {
            ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
            while((c=in.read())>=0){if(c==13){if(in.read()!=10)throw new IllegalStateException();return b.toString(StandardCharsets.UTF_8);}b.write(c);}return null;
        }
        public void close() throws Exception {server.close();thread.join(2000);}
    }
}
