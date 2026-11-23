package com.knode.content.relay;

import com.knode.content.auth.AuthConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import java.net.InetAddress;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RelayConfiguration {
    @Bean(destroyMethod="close")
    RelayStream relayStream(AuthConfiguration.Settings auth,
            @Value("${KNODE_CONTENT_RELAY_ENABLED:false}") boolean enabled,
            @Value("${KNODE_CONTENT_RELAY_WORKER_ENABLED:false}") boolean worker,
            @Value("${KNODE_CONTENT_RELAY_REDIS_HOST:}") String host,
            @Value("${KNODE_CONTENT_RELAY_REDIS_PORT:}") String portText,
            @Value("${KNODE_CONTENT_RELAY_REDIS_USERNAME:}") String user,
            @Value("${KNODE_CONTENT_RELAY_REDIS_PASSWORD:}") String password,
            @Value("${KNODE_AUTH_REDIS_USERNAME}") String authUser,
            @Value("${KNODE_AUTH_REDIS_PASSWORD}") String authPassword,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_USERNAME:}") String counterUser,
            @Value("${KNODE_CONTENT_COUNTER_REDIS_PASSWORD:}") String counterPassword) {
        if(!enabled) {
            if(worker) throw new IllegalArgumentException("Relay worker requires enabled relay");
            return new RelayStream.Disabled();
        }
        int port;
        try { port=Integer.parseInt(portText); } catch(NumberFormatException failure) { throw new IllegalArgumentException("Invalid relay configuration"); }
        if(!numeric(host) || port<1 || port>65535 || user.isBlank() || user.length()>128 || password.isBlank() || password.length()>512
                || user.equals(authUser) || password.equals(authPassword)
                || (!counterUser.isBlank() && user.equals(counterUser)) || (!counterPassword.isBlank() && password.equals(counterPassword))
                || user.chars().anyMatch(Character::isISOControl) || password.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid relay configuration");
        var redis=new RedisStandaloneConfiguration(host,port); redis.setUsername(user); redis.setPassword(RedisPassword.of(password));
        var options=ClientOptions.builder().autoReconnect(false)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(400)).build())
                .sslOptions(SslOptions.builder().handshakeTimeout(Duration.ofMillis(500)).build()).build();
        var client=LettuceClientConfiguration.builder().useSsl().and().clientOptions(options)
                .commandTimeout(Duration.ofMillis(800)).build();
        var factory=new LettuceConnectionFactory(redis,client); factory.setShareNativeConnection(false);
        factory.afterPropertiesSet(); factory.start();
        var template=new StringRedisTemplate(factory); template.afterPropertiesSet();
        return new RedisRelayStream(template,factory,"knode:"+auth.environment()+":v1:events:content");
    }
    private static boolean numeric(String host) {
        try {
            if(host==null || host.length()>45) return false;
            if(host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                for(String part:host.split("\\.")) if(Integer.parseInt(part)>255) return false;
                return true;
            }
            return host.indexOf(':')>=0 && host.matches("[0-9A-Fa-f:.]+") && InetAddress.getByName(host)!=null;
        } catch(Exception failure) { return false; }
    }
}
