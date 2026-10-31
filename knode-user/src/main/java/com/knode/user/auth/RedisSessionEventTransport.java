package com.knode.user.auth;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;

/** Dedicated credentials and connection: never reuse the session writer connection. */
public final class RedisSessionEventTransport implements SessionEventTransport {
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;
    private final String stream,probe;
    public RedisSessionEventTransport(Environment env) {
        String environment=required(env,"KNODE_AUTH_ENVIRONMENT");
        if (!environment.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("Invalid auth environment");
        String user=required(env,"KNODE_SESSION_EVENTS_REDIS_USERNAME");
        if (user.equals(env.getProperty("KNODE_AUTH_REDIS_READ_USERNAME")) || user.equals(env.getProperty("KNODE_AUTH_REDIS_WRITE_USERNAME")))
            throw new IllegalArgumentException("Stream identity must differ from session identities");
        stream=env.getProperty("KNODE_SESSION_EVENTS_STREAM_KEY","knode:"+environment+":v1:events:session");
        probe=required(env,"KNODE_SESSION_EVENTS_PROBE_KEY");
        if (!validKey(stream) || !validKey(probe) || stream.equals(probe)
                || stream.matches("knode:[a-z0-9-]+:v1:auth:.*") || probe.matches("knode:[a-z0-9-]+:v1:auth:.*"))
            throw new IllegalArgumentException("Distinct non-auth stream and probe keys required");
        int port=Integer.parseInt(required(env,"KNODE_SESSION_EVENTS_REDIS_PORT"));
        int db=Integer.parseInt(env.getProperty("KNODE_SESSION_EVENTS_REDIS_DATABASE","0"));
        if (port<1 || port>65535 || db<0) throw new IllegalArgumentException("Invalid event Redis configuration");
        var config=new RedisStandaloneConfiguration(required(env,"KNODE_SESSION_EVENTS_REDIS_HOST"),port);
        config.setUsername(user);config.setPassword(RedisPassword.of(required(env,"KNODE_SESSION_EVENTS_REDIS_PASSWORD")));config.setDatabase(db);
        var builder=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .clientOptions(io.lettuce.core.ClientOptions.builder()
                        .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build());
        String tls=env.getProperty("KNODE_SESSION_EVENTS_REDIS_TLS","true");
        if (!"true".equals(tls) && !"false".equals(tls)) throw new IllegalArgumentException("Invalid event Redis TLS");
        if ("true".equals(tls)) builder.useSsl();
        factory=new LettuceConnectionFactory(config,builder.build());factory.afterPropertiesSet();factory.start();
        redis=new StringRedisTemplate(factory);
    }
    private static String required(Environment env,String key) {
        String value=env.getProperty(key);if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+key);return value;
    }
    private static boolean validKey(String key) {return key!=null && key.matches("[A-Za-z0-9:_-]{1,255}");}
    @Override public boolean enabled() {return true;}
    @Override public void publish(String envelope) {append(redis,stream,envelope);}
    static void append(StringRedisTemplate redis,String stream,String envelope) {
        if (redis.opsForStream().add(stream,Map.of("event",envelope))==null)
            throw new IllegalStateException("Stream append not confirmed");
    }
    @Override public boolean probe() {return probe(redis,probe);}
    static boolean probe(StringRedisTemplate redis,String probe) {
        try {
            // Queue XADD and EXPIRE on one connection so a crash cannot leave a persistent probe.
            List<Object> result=redis.execute(new SessionCallback<List<Object>>() {
                @Override @SuppressWarnings({"unchecked","rawtypes"})
                public <K,V> List<Object> execute(RedisOperations<K,V> operations) {
                    operations.multi();
                    try {
                        operations.opsForStream().add((K)probe,(Map)Map.of("probe","1"));
                        operations.expire((K)probe,Duration.ofSeconds(10));
                        return operations.exec();
                    } catch (RuntimeException e) {
                        try {operations.discard();} catch (RuntimeException ignored) {}
                        throw e;
                    }
                }
            });
            if (result==null || result.size()!=2 || result.get(0)==null || !Boolean.TRUE.equals(result.get(1))) return false;
            return Boolean.TRUE.equals(redis.delete(probe));
        } catch (RuntimeException e) {return false;}
        finally {try {redis.delete(probe);} catch (RuntimeException ignored) {}}
    }
    @Override public void close() {factory.destroy();}
}
