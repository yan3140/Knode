package com.knode.governance;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Each array is validated completely before replacing the last valid Sentinel rule set. */
public class GovernanceRules implements ApplicationRunner, HealthIndicator {
    private static final Logger LOG = LoggerFactory.getLogger(GovernanceRules.class);
    private static final int MAX_CONTENT = 1024 * 1024;
    private static final int MAX_RULES = 5000;
    private static final int MAX_QUEUE_MS = 1000;
    private final ConfigService config;
    private final ObjectMapper mapper;
    private final ObjectReader strictJson;
    private final String group;
    private final String flowId;
    private final String degradeId;
    private final long retryMin, retryMax;
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<String, Boolean> loaded = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final java.util.Set<String> invalidReported = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor scheduler;

    public GovernanceRules(ConfigService config, ObjectMapper mapper, String app, String group, long min, long max) {
        if (app == null || !app.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || group == null || group.isBlank()
                || min < 1 || max < min || max > 300000) throw new IllegalArgumentException("Invalid governance configuration");
        this.config = config; this.mapper = mapper; this.group = group; this.retryMin = min; this.retryMax = max;
        this.strictJson = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.flowId = app + ".flow.json"; this.degradeId = app + ".degrade.json";
        loaded.put(flowId, false); loaded.put(degradeId, false);
        scheduler = new ScheduledThreadPoolExecutor(2, job -> {
            Thread thread = new Thread(job, "governance-rule-retry"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @Override public void run(ApplicationArguments args) {
        if (started.compareAndSet(false, true)) for (String id : loaded.keySet()) scheduler.execute(() -> register(id, 0));
    }

    private void register(String id, int attempt) {
        if (closed.get()) return;
        AtomicBoolean received = new AtomicBoolean();
        Listener listener = new Listener() {
            @Override public Executor getExecutor() { return null; }
            @Override public void receiveConfigInfo(String value) {
                synchronized (GovernanceRules.this) {
                    if (closed.get() || listeners.get(id) != this) return;
                    received.set(true); apply(id, value);
                }
            }
        };
        listeners.put(id, listener);
        try {
            String initial = config.getConfigAndSignListener(id, group, 3000, listener);
            synchronized (this) {
                if (closed.get()) { config.removeListener(id, group, listener); return; }
                if (!received.get()) apply(id, initial);
                attempts.put(id, 0);
            }
        } catch (Exception e) {
            synchronized (this) { loaded.put(id, false); listeners.remove(id, listener); }
            try { config.removeListener(id, group, listener); } catch (RuntimeException ignored) { }
            int next = Math.min(attempt + 1, 30); attempts.put(id, next);
            long delay = Math.min(retryMax, retryMin * (1L << Math.min(attempt, 16)));
            if ((next & (next - 1)) == 0)
                LOG.warn("Rule listener registration failed for {}; retry in {} ms (attempt {})", id, delay, next);
            if (!closed.get()) try { scheduler.schedule(() -> register(id, next), delay, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException e2) { if (!closed.get()) throw e2; }
        }
    }

    synchronized void apply(String id, String raw) {
        try {
            if (raw == null || raw.isBlank() || raw.length() > MAX_CONTENT)
                throw new IllegalArgumentException("Missing or oversized rules");
            JsonNode array = strictJson.readTree(raw);
            if (!array.isArray() || array.size() > MAX_RULES) throw new IllegalArgumentException("Invalid rule array");
            if (flowId.equals(id)) {
                List<FlowRule> rules = new ArrayList<>();
                for (JsonNode value : array) {
                    FlowRule rule = mapper.treeToValue(value, FlowRule.class);
                    if (!validFlow(rule)) throw new IllegalArgumentException("Invalid flow rule");
                    rules.add(rule);
                }
                FlowRuleManager.loadRules(rules);
            } else if (degradeId.equals(id)) {
                List<DegradeRule> rules = new ArrayList<>();
                for (JsonNode value : array) {
                    DegradeRule rule = mapper.treeToValue(value, DegradeRule.class);
                    if (rule == null || !Double.isFinite(rule.getCount()) || !Double.isFinite(rule.getSlowRatioThreshold())
                            || !DegradeRuleManager.isValidRule(rule)) throw new IllegalArgumentException("Invalid degrade rule");
                    rules.add(rule);
                }
                DegradeRuleManager.loadRules(rules);
            } else throw new IllegalArgumentException("Unknown data ID");
            loaded.put(id, true);
            invalidReported.remove(id);
        } catch (Exception failure) {
            loaded.put(id, false);
            if (invalidReported.add(id)) LOG.warn("Rule update rejected for {}", id);
        }
    }

    static boolean validFlow(FlowRule rule) {
        return rule != null && Double.isFinite(rule.getCount()) && rule.getGrade() >= 0 && rule.getGrade() <= 1
                && rule.getStrategy() >= 0 && rule.getStrategy() <= 2
                && rule.getControlBehavior() >= 0 && rule.getControlBehavior() <= 3
                && !rule.isClusterMode() && rule.getMaxQueueingTimeMs() <= MAX_QUEUE_MS
                && FlowRuleUtil.isValidRule(rule);
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
        listeners.forEach((id, listener) -> {
            try { config.removeListener(id, group, listener); } catch (RuntimeException ignored) { }
        });
        try { config.shutDown(); } catch (Exception ignored) { LOG.warn("Governance Nacos client shutdown failed"); }
    }
}
