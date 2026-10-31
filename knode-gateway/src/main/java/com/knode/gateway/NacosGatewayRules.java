package com.knode.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("nacosRules")
@ConditionalOnProperty(name = "knode.governance.nacos.enabled", havingValue = "true")
public class NacosGatewayRules implements ApplicationRunner, HealthIndicator {
    private static final Logger LOG = LoggerFactory.getLogger(NacosGatewayRules.class);
    private final ConfigService config;
    private final ObjectMapper mapper;
    private final String group;
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loaded = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor scheduler;
    private final long retryMinMs;
    private final long retryMaxMs;
    private static final String FLOW = "knode-gateway.gw-flow.json";
    private static final String API = "knode-gateway.gw-api-group.json";

    @Autowired
    public NacosGatewayRules(@org.springframework.beans.factory.annotation.Qualifier("governanceConfigService") ConfigService config, ObjectMapper mapper,
            @Value("${knode.governance.nacos.group:KNODE_SENTINEL}") String group,
            @Value("${knode.governance.nacos.retry-min-ms:1000}") long retryMinMs,
            @Value("${knode.governance.nacos.retry-max-ms:30000}") long retryMaxMs) {
        if (retryMinMs < 1 || retryMaxMs < retryMinMs || retryMaxMs > 300000)
            throw new IllegalArgumentException("Invalid governance retry bounds");
        this.config = config;
        this.mapper = mapper;
        this.group = group;
        this.retryMinMs = retryMinMs;
        this.retryMaxMs = retryMaxMs;
        this.scheduler = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "nacos-rule-retry");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
        loaded.put(FLOW, false);
        loaded.put(API, false);
    }

    NacosGatewayRules(ConfigService config, ObjectMapper mapper, String group) {
        this(config, mapper, group, 20, 100);
    }

    @Override public void run(ApplicationArguments args) {
        if (started.compareAndSet(false, true)) {
            for (String id : loaded.keySet()) scheduler.execute(() -> register(id, 0));
        }
    }

    private void register(String id, int attempt) {
        if (closed.get()) return;
        AtomicBoolean received = new AtomicBoolean();
        Listener listener = new Listener() {
            @Override public Executor getExecutor() { return null; }
            @Override public void receiveConfigInfo(String value) {
                synchronized (NacosGatewayRules.this) {
                    if (closed.get() || listeners.get(id) != this) return;
                    received.set(true);
                    apply(id, value);
                }
            }
        };
        listeners.put(id, listener);
        try {
            String value = config.getConfigAndSignListener(id, group, 3000, listener);
            synchronized (this) {
                if (closed.get()) {
                    config.removeListener(id, group, listener);
                    return;
                }
                if (!received.get()) apply(id, value);
                attempts.put(id, 0);
            }
        } catch (Exception failure) {
            synchronized (this) {
                loaded.put(id, false);
                listeners.remove(id, listener);
            }
            try { config.removeListener(id, group, listener); } catch (RuntimeException ignored) { }
            int next = Math.min(attempt + 1, 30);
            attempts.put(id, next);
            long delay = Math.min(retryMaxMs, retryMinMs * (1L << Math.min(attempt, 16)));
            LOG.warn("Governance listener registration failed for {}; retry in {} ms (attempt {})", id, delay, next);
            if (!closed.get()) {
                try { scheduler.schedule(() -> register(id, next), delay, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.RejectedExecutionException shutdownRace) {
                    if (!closed.get()) throw shutdownRace;
                }
            }
        }
    }

    synchronized void apply(String id, String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("missing rule data");
            JsonNode root = mapper.readTree(raw);
            if (!root.isArray()) throw new IllegalArgumentException("rule data must be an array");
            if (FLOW.equals(id)) {
                Set<GatewayFlowRule> rules = mapper.readValue(raw, new TypeReference<Set<GatewayFlowRule>>() {});
                if (rules.stream().anyMatch(rule -> !GatewayRuleManager.isValidRule(rule)))
                    throw new IllegalArgumentException("invalid gateway rules");
                GatewayRuleManager.loadRules(rules);
            } else if (API.equals(id)) {
                Set<ApiDefinition> definitions = new LinkedHashSet<>();
                Set<String> names = new LinkedHashSet<>();
                for (JsonNode entry : root) {
                    String name = entry.path("apiName").asText("");
                    JsonNode items = entry.path("predicateItems");
                    if (name.isBlank() || !names.add(name) || !items.isArray() || items.isEmpty())
                        throw new IllegalArgumentException("invalid or duplicate api group");
                    Set<ApiPredicateItem> predicates = new LinkedHashSet<>();
                    for (JsonNode item : items) {
                        String pattern = item.path("pattern").asText("");
                        int strategy = item.path("matchStrategy").asInt(0);
                        if (pattern.isBlank() || strategy < 0 || strategy > 2) throw new IllegalArgumentException("invalid predicate");
                        if (strategy == 2) java.util.regex.Pattern.compile(pattern);
                        predicates.add(new ApiPathPredicateItem().setPattern(pattern).setMatchStrategy(strategy));
                    }
                    ApiDefinition definition = new ApiDefinition(name).setPredicateItems(predicates);
                    if (!GatewayApiDefinitionManager.isValidApi(definition) || !definitions.add(definition))
                        throw new IllegalArgumentException("invalid or duplicate api group");
                }
                GatewayApiDefinitionManager.loadApiDefinitions(definitions);
            } else throw new IllegalArgumentException("unknown rule type");
            loaded.put(id, true);
        } catch (Exception failure) {
            loaded.put(id, false);
            LOG.warn("Governance rule update rejected for {}", id);
        }
    }

    @Override public Health health() {
        if (loaded.values().stream().allMatch(Boolean::booleanValue) && "UP".equals(config.getServerStatus()))
            return Health.up().build();
        return Health.outOfService().withDetail("reason", "Nacos rule state unavailable")
                .withDetail("retryAttempts", Map.copyOf(attempts)).build();
    }

    @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        listeners.forEach((id, listener) -> config.removeListener(id, group, listener));
        try { config.shutDown(); } catch (Exception failure) { LOG.warn("Nacos rule client shutdown failed"); }
    }
}
