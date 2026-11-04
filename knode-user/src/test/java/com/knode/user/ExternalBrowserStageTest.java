package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserStageTest {
    @TempDir Path temp;
    @Test void exitedFrontendKeepsItsStageAndSelectedPortInManifest() throws Exception {
        var test=new ExternalStackIT();
        Field manifest=ExternalStackIT.class.getDeclaredField("manifest");manifest.setAccessible(true);
        manifest.set(test,temp.resolve("manifest.json"));
        Field enabled=ExternalStackIT.class.getDeclaredField("browserEnabled");enabled.setAccessible(true);enabled.set(test,true);
        Method stage=ExternalStackIT.class.getDeclaredMethod("browserStage",String.class);stage.setAccessible(true);
        stage.invoke(test,"blog-frontend-wait");
        Process exited=new Process() {
            public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public InputStream getInputStream(){return InputStream.nullInputStream();}
            public InputStream getErrorStream(){return InputStream.nullInputStream();}
            public int waitFor(){return 1;}
            public int exitValue(){return 1;}
            public void destroy(){}
        };
        Method await=ExternalStackIT.class.getDeclaredMethod("awaitFrontend",String.class,Process.class,int.class);await.setAccessible(true);
        try (var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            int port=listener.getLocalPort();
            var failure=assertThrows(InvocationTargetException.class,()->await.invoke(test,"blog",exited,port));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
            var body=new ObjectMapper().readTree(temp.resolve("manifest.json").toFile());
            assertEquals("blog-frontend-wait",body.path("browserStage").asText());
            assertEquals(port,body.path("browserProbes").path("blog").path("selectedPort").asInt());
            assertFalse(body.path("browserProbes").path("blog").path("processAlive").asBoolean());
            assertEquals("ProcessExited",body.path("browserProbes").path("blog").path("lastExceptionType").asText());
            assertTrue(body.path("browserProbes").path("blog").path("selectedPortListening").asBoolean());
        }
        Method audit=ExternalStackIT.class.getDeclaredMethod("auditStage");audit.setAccessible(true);
        assertEquals("blog-frontend-wait",audit.invoke(test));
    }
}
