package com.knode.user.config;

import com.knode.user.auth.*;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.HashMap;
import java.util.Map;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class UserConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(); }
    private Map<String,String> settings(Environment env, String prefix) {
        var result=new HashMap<String,String>();
        for (String suffix : new String[]{"HOST","PORT","USERNAME","PASSWORD","DATABASE","TLS"}) {
            String value=env.getProperty(prefix+suffix);
            if (value!=null) result.put(prefix+suffix,value);
        }
        return result;
    }
    @Bean(name="authReadConnection", destroyMethod="destroy")
    LettuceConnectionFactory readConnection(Environment env) {
        String p="KNODE_AUTH_REDIS_READ_"; return SessionRedisConnections.create(settings(env,p),p);
    }
    @Bean(name="authWriteConnection", destroyMethod="destroy")
    LettuceConnectionFactory writeConnection(Environment env) {
        String p="KNODE_AUTH_REDIS_WRITE_"; return SessionRedisConnections.create(settings(env,p),p);
    }
    @Bean SessionStore.Reader sessionReader(@Qualifier("authReadConnection") LettuceConnectionFactory connection, Environment env) {
        return new RedisSessionReader(new StringRedisTemplate(connection),env.getRequiredProperty("KNODE_AUTH_ENVIRONMENT"));
    }
    @Bean SessionStore.Writer sessionWriter(@Qualifier("authWriteConnection") LettuceConnectionFactory connection, Environment env, Clock clock) {
        return new RedisSessionWriter(new StringRedisTemplate(connection),env.getRequiredProperty("KNODE_AUTH_ENVIRONMENT"),clock);
    }
    @Bean UserTokens userTokens(Environment env, Clock clock, SessionStore.Reader reader, SessionStore.Writer writer) {
        String kid=env.getRequiredProperty("KNODE_AUTH_SIGNING_KID");
        String privateFile=env.getProperty("KNODE_AUTH_SIGNING_PRIVATE_FILE");
        String privateEnvironment=env.getProperty("KNODE_AUTH_SIGNING_PRIVATE_ENV");
        if ((privateFile==null)==(privateEnvironment==null)) throw new IllegalStateException("Set exactly one private key source");
        RSAPrivateKey privateKey=privateFile!=null ? KeyMaterial.privateFromFile(Path.of(privateFile)) : KeyMaterial.privateFromEnvironment(privateEnvironment);
        var publicKey=KeyMaterial.publicFromFile(Path.of(env.getRequiredProperty("KNODE_AUTH_SIGNING_PUBLIC_FILE")));
        return new UserTokens(privateKey,kid,Map.of(kid,publicKey),clock,reader,writer);
    }
}
