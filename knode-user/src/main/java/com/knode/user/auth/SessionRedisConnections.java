package com.knode.user.auth;

import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Separate settings and connection factories enable distinct read and write ACL identities. */
public final class SessionRedisConnections {
    private SessionRedisConnections() {}
    public static LettuceConnectionFactory create(Map<String,String> environment, String prefix) {
        if (!"KNODE_AUTH_REDIS_READ_".equals(prefix) && !"KNODE_AUTH_REDIS_WRITE_".equals(prefix))
            throw new IllegalArgumentException("Unrecognized connection prefix");
        String host=required(environment,prefix+"HOST");
        int port=Integer.parseInt(required(environment,prefix+"PORT"));
        if (port<1 || port>65535) throw new IllegalArgumentException("Invalid Redis port");
        String user=required(environment,prefix+"USERNAME");
        String password=required(environment,prefix+"PASSWORD");
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration(host,port);
        config.setUsername(user); config.setPassword(RedisPassword.of(password));
        String db=environment.getOrDefault(prefix+"DATABASE","0");
        int database=Integer.parseInt(db);
        if (database<0) throw new IllegalArgumentException("Invalid Redis database");
        config.setDatabase(database);
        var builder=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2));
        String tls=environment.getOrDefault(prefix+"TLS","true");
        if (!"true".equals(tls) && !"false".equals(tls)) throw new IllegalArgumentException("Invalid TLS flag");
        if (Boolean.parseBoolean(tls)) builder.useSsl();
        return new LettuceConnectionFactory(config,builder.build());
    }
    private static String required(Map<String,String> values,String key) {
        String value=values.get(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing setting "+key);
        return value;
    }
}
