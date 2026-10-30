package com.knode.user.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class ExternalConfigurationTest {
    @Test void distinctRedisFactoriesUseDistinctExternalSettings() {
        var env=new HashMap<String,String>();
        for (String role : new String[]{"READ","WRITE"}) {
            String prefix="KNODE_AUTH_REDIS_"+role+"_";
            env.put(prefix+"HOST","localhost"); env.put(prefix+"PORT",role.equals("READ")?"10001":"10002");
            env.put(prefix+"USERNAME",role.toLowerCase()); env.put(prefix+"PASSWORD","example-only");
        }
        var read=SessionRedisConnections.create(env,"KNODE_AUTH_REDIS_READ_");
        var write=SessionRedisConnections.create(env,"KNODE_AUTH_REDIS_WRITE_");
        assertEquals(10001,read.getStandaloneConfiguration().getPort());
        assertEquals(10002,write.getStandaloneConfiguration().getPort());
        assertEquals("read",read.getStandaloneConfiguration().getUsername());
        assertEquals("write",write.getStandaloneConfiguration().getUsername());
        assertTrue(read.isUseSsl());
        env.remove("KNODE_AUTH_REDIS_WRITE_PASSWORD");
        assertThrows(IllegalArgumentException.class,() -> SessionRedisConnections.create(env,"KNODE_AUTH_REDIS_WRITE_"));
    }
    @Test void loadsOnlyGeneratedTestKeysFromFiles(@TempDir Path dir) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair=generator.generateKeyPair();
        String privatePem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
        String publicPem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n";
        Path privateFile=Files.writeString(dir.resolve("private.pem"),privatePem);
        Path publicFile=Files.writeString(dir.resolve("public.pem"),publicPem);
        assertEquals(pair.getPrivate(),KeyMaterial.privateFromFile(privateFile));
        assertEquals(pair.getPublic(),KeyMaterial.publicFromFile(publicFile));
    }
}
