package com.knode.content.relay;

import com.knode.content.auth.AuthConfiguration;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;

class RelayConfigurationTest {
    final RelayConfiguration config=new RelayConfiguration();
    final AuthConfiguration.Settings auth=new AuthConfiguration.Settings("test");
    @Test void relayDefaultsOffAndRequiresIndependentTlsConfiguration() throws Exception {
        assertFalse(config.relayStream(auth,false,false,"","","","","auth","auth-pass","counter","counter-pass").enabled());
        assertThrows(IllegalArgumentException.class,() -> config.relayStream(auth,false,true,"","","","","auth","auth-pass","counter","counter-pass"));
        assertThrows(IllegalArgumentException.class,() -> config.relayStream(auth,true,false,"127.0.0.1","","relay","relay-pass","auth","auth-pass","counter","counter-pass"));
        assertThrows(IllegalArgumentException.class,() -> config.relayStream(auth,true,false,"example.invalid","6379","relay","relay-pass","auth","auth-pass","counter","counter-pass"));
        assertThrows(IllegalArgumentException.class,() -> config.relayStream(auth,true,false,"127.0.0.1","6379","auth","relay-pass","auth","auth-pass","counter","counter-pass"));
        assertThrows(IllegalArgumentException.class,() -> config.relayStream(auth,true,false,"127.0.0.1","6379","relay","counter-pass","auth","auth-pass","counter","counter-pass"));
        try(RelayStream stream=config.relayStream(auth,true,false,"127.0.0.1","6379","relay","relay-pass","auth","auth-pass","counter","counter-pass")) {
            assertTrue(stream.enabled());
            Field f=RedisRelayStream.class.getDeclaredField("factory");f.setAccessible(true);
            LettuceConnectionFactory factory=(LettuceConnectionFactory)f.get(stream);
            assertTrue(factory.getClientConfiguration().isUseSsl());assertFalse(factory.getShareNativeConnection());
            assertEquals("relay",factory.getStandaloneConfiguration().getUsername());
            Field key=RedisRelayStream.class.getDeclaredField("key");key.setAccessible(true);
            assertEquals("knode:test:v1:events:content",new String((byte[])key.get(stream),StandardCharsets.UTF_8));
        }
    }
}
