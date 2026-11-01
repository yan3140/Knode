package com.knode.user.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Single fixed Data ID; remote data can only change a bounded relay batch size. */
@Component
public final class ControlledRelaySettings implements ApplicationRunner,HealthIndicator,AutoCloseable {
    private static final String DATA_ID="knode-user.relay.json";
    private final AtomicInteger batch=new AtomicInteger(50);
    private final AtomicBoolean loaded=new AtomicBoolean();
    private final boolean enabled;
    private final ConfigService config;
    private final String group;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ScheduledExecutorService retry=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"user-config-retry");t.setDaemon(true);return t;
    });
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile Listener listener;

    public ControlledRelaySettings(Environment env) throws Exception {
        String flag=env.getProperty("USER_NACOS_CONFIG_ENABLED","false");
        if (!"true".equals(flag) && !"false".equals(flag)) throw new IllegalArgumentException("Invalid User Nacos config flag");
        enabled="true".equals(flag);
        group=value(env,"USER_NACOS_CONFIG_GROUP","USER_NACOS_GROUP","NACOS_GROUP");
        if (!enabled) {config=null;return;}
        String address=value(env,"USER_NACOS_SERVER_ADDR","NACOS_SERVER_ADDR");
        String namespace=value(env,"USER_NACOS_NAMESPACE","NACOS_NAMESPACE");
        if (address==null || namespace==null || group==null || namespace.isBlank() || "public".equalsIgnoreCase(namespace) || group.isBlank())
            throw new IllegalStateException("Explicit User Nacos address, namespace and config group required");
        Properties props=new Properties();props.put(PropertyKeyConst.SERVER_ADDR,address);props.put(PropertyKeyConst.NAMESPACE,namespace);
        String username=value(env,"USER_NACOS_USERNAME","NACOS_USERNAME"),password=value(env,"USER_NACOS_PASSWORD","NACOS_PASSWORD");
        if ((username==null)!=(password==null)) throw new IllegalStateException("Provide both Nacos credential values");
        if (username!=null) props.put(PropertyKeyConst.USERNAME,username);
        if (password!=null) props.put(PropertyKeyConst.PASSWORD,password);
        config=NacosFactory.createConfigService(props);
    }
    private static String value(Environment env,String... keys) {
        for (String key:keys) {String v=env.getProperty(key);if (v!=null && !v.isBlank()) return v;}
        return null;
    }
    public int batchSize() {return batch.get();}
    public boolean enabled() {return enabled;}
    @Override public void run(ApplicationArguments args) {if (enabled) retry.execute(this::register);}
    private void register() {
        if (closed.get()) return;
        AtomicBoolean received=new AtomicBoolean();
        Listener next=new Listener() {
            @Override public java.util.concurrent.Executor getExecutor() {return null;}
            @Override public void receiveConfigInfo(String raw) {
                synchronized (ControlledRelaySettings.this) {
                    if (!closed.get() && listener==this) {received.set(true);apply(raw);}
                }
            }
        };
        listener=next;
        try {
            String raw=config.getConfigAndSignListener(DATA_ID,group,3000,next);
            synchronized (this) {if (!received.get()) apply(raw);}
        } catch (Exception e) {
            loaded.set(false);listener=null;
            try {config.removeListener(DATA_ID,group,next);} catch (RuntimeException ignored) {}
            if (!closed.get()) retry.schedule(this::register,5,TimeUnit.SECONDS);
        }
    }
    private synchronized void apply(String raw) {
        try {
            if (raw==null || raw.length()>1024) throw new IllegalArgumentException();
            JsonNode root=json.readTree(raw);
            if (root==null || !root.isObject() || root.size()!=1 || !root.path("sessionEventRelayBatchSize").isInt())
                throw new IllegalArgumentException();
            int n=root.path("sessionEventRelayBatchSize").intValue();if (n<1 || n>100) throw new IllegalArgumentException();
            batch.set(n);loaded.set(true);
        } catch (Exception e) {loaded.set(false);}
    }
    @Override public Health health() {
        if (!enabled) return Health.up().withDetail("status","DISABLED").build();
        return loaded.get() && "UP".equals(config.getServerStatus())?Health.up().build():
                Health.outOfService().withDetail("reason","User relay configuration unavailable").build();
    }
    @Override public void close() {
        closed.set(true);retry.shutdownNow();
        if (config!=null) {
            if (listener!=null) try {config.removeListener(DATA_ID,group,listener);} catch (RuntimeException ignored) {}
            try {config.shutDown();} catch (Exception ignored) {}
        }
    }
}
