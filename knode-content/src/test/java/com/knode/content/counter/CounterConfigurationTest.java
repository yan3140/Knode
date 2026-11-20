package com.knode.content.counter;

import com.knode.content.auth.AuthConfiguration;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class CounterConfigurationTest {
    final CounterConfiguration config=new CounterConfiguration();
    final AuthConfiguration.Settings auth=new AuthConfiguration.Settings("test");
    @Test void disabledIsSafeAndWorkerCannotRunAlone() {
        assertFalse(config.counterStore(auth,false,false,"","","","","auth-user","auth-password").enabled());
        assertThrows(IllegalArgumentException.class,() -> config.counterStore(auth,false,true,"","","","","auth-user","auth-password"));
    }
    @Test void enabledRequiresIndependentCredentialsAndNumericHost() throws Exception {
        assertThrows(IllegalArgumentException.class,() -> config.counterStore(auth,true,false,"127.0.0.1","","business","business-pass","auth-user","auth-password"));
        assertThrows(IllegalArgumentException.class,() -> config.counterStore(auth,true,false,"redis.example","6379","business","business-pass","auth-user","auth-password"));
        assertThrows(IllegalArgumentException.class,() -> config.counterStore(auth,true,false,"127.0.0.1","6379","auth-user","business-pass","auth-user","auth-password"));
        assertThrows(IllegalArgumentException.class,() -> config.counterStore(auth,true,false,"127.0.0.1","6379","business","auth-password","auth-user","auth-password"));
        try(CounterStore store=config.counterStore(auth,true,false,"127.0.0.1","6379","business","business-pass","auth-user","auth-password")) {
            assertTrue(store.enabled());
            Field field=RedisCounterStore.class.getDeclaredField("factory");field.setAccessible(true);
            LettuceConnectionFactory factory=(LettuceConnectionFactory)field.get(store);
            assertTrue(factory.getClientConfiguration().isUseSsl());
            assertFalse(factory.getShareNativeConnection());
            assertEquals("business",factory.getStandaloneConfiguration().getUsername());
        }
    }

    @Test void missingEnabledConfigurationFailsApplicationStartup() {
        new ApplicationContextRunner().withUserConfiguration(CounterConfiguration.class)
            .withBean(AuthConfiguration.Settings.class,() -> auth)
            .withPropertyValues("KNODE_CONTENT_COUNTER_ENABLED=true",
                "KNODE_AUTH_REDIS_USERNAME=auth-user","KNODE_AUTH_REDIS_PASSWORD=auth-password")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
