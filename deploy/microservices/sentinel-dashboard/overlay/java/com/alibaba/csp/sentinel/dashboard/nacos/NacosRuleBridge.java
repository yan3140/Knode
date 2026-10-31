package com.alibaba.csp.sentinel.dashboard.nacos;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.ApiDefinitionEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.GatewayFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.FlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.domain.Result;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

/** Keeps the official dashboard endpoints and authorization interceptor; Nacos is the authority. */
@Component
public class NacosRuleBridge implements HandlerInterceptor {
    private final ObjectProvider<ConfigService> configs;
    private final ObjectMapper mapper;
    private final String group;

    public NacosRuleBridge(@Qualifier("dashboardNacosConfig") ObjectProvider<ConfigService> configs,
            ObjectMapper mapper, @Value("${dashboard.nacos.group:KNODE_SENTINEL}") String group) {
        this.configs = configs; this.mapper = mapper; this.group = group;
    }

    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = UrlPathHelper.defaultInstance.getLookupPathForRequest(req);
        String kind = kind(path);
        res.setContentType("application/json;charset=UTF-8");
        try {
            if (kind == null) throw new IllegalArgumentException("Unsupported rule path");
            if (!allowed(path, req.getMethod())) throw new IllegalArgumentException("Unsupported rule operation");
            ConfigService config = configs.getIfAvailable();
            if (config == null) throw new IllegalStateException("Nacos rule storage is not configured");
            ObjectNode input = requestData(req);
            String app = first(req.getParameter("app"), "");
            if (input.hasNonNull("app") && !app.equals(input.path("app").asText()))
                throw new IllegalArgumentException("Application mismatch");
            if (!app.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                throw new IllegalArgumentException("Invalid application name");
            String dataId = app + "." + kind + ".json";
            String raw = config.getConfig(dataId, group, 3000);
            if (raw == null || raw.trim().isEmpty()) throw new IllegalStateException("Rule data missing; initialize explicitly");
            JsonNode parsed = mapper.readTree(raw);
            if (!parsed.isArray()) throw new IllegalArgumentException("Nacos rule data must be an array");
            ArrayNode current = (ArrayNode) parsed;
            validateAll(kind, current);
            String version = digest(raw);
            boolean read = "GET".equals(req.getMethod());
            if (read) {
                ArrayNode result = mapper.createArrayNode();
                Set<Long> seen = new HashSet<Long>();
                for (int index = 0; index < current.size(); index++) {
                    long id = id(kind, app, index, current.get(index));
                    if (!seen.add(id)) throw new IllegalStateException("Rule identifier collision");
                    result.add(toUi(kind, app, req, current.get(index), id));
                }
                res.setHeader("X-Nacos-Version", version);
                res.setHeader("X-Nacos-Application", app);
                res.setHeader("X-Nacos-Type", kind);
                res.getWriter().write(mapper.writeValueAsString(Result.ofSuccess(result)));
                return false;
            }
            String expected = req.getHeader("X-Nacos-Version");
            if (expected == null || !expected.matches("[a-f0-9]{32}") || !version.equals(expected)) {
                res.getWriter().write(mapper.writeValueAsString(Result.ofFail(409, "Rule version changed; reload before editing")));
                return false;
            }
            String method = req.getMethod();
            boolean delete = "DELETE".equals(method) || path.endsWith("/delete.json");
            boolean create = "POST".equals(method) && (path.endsWith("/new.json") || path.equals("/v1/flow/rule") || path.equals("/degrade/rule"));
            ArrayNode next = current.deepCopy();
            ObjectNode result = mapper.createObjectNode();
            if (create) {
                JsonNode converted = toRule(kind, input);
                next.add(converted);
                result = toUi(kind, app, req, converted, id(kind, app, next.size()-1, converted));
            } else {
                String idValue = first(req.getParameter("id"), input.path("id").asText(""));
                if (idValue.isEmpty() && path.startsWith("/degrade/rule/")) idValue = path.substring("/degrade/rule/".length());
                long target = Long.parseLong(idValue);
                int found = -1;
                for (int index = 0; index < current.size(); index++)
                    if (id(kind, app, index, current.get(index)) == target) {
                        if (found >= 0) throw new IllegalStateException("Rule identifier collision");
                        found = index;
                    }
                if (found < 0) throw new IllegalArgumentException("Rule not found; reload");
                if (delete) {
                    next.remove(found); result.put("id", target);
                } else {
                    ObjectNode merged = toUi(kind, app, req, current.get(found), target);
                    Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        if (!java.util.Arrays.asList("id","app","ip","port","gmtCreate","gmtModified").contains(field.getKey()))
                            merged.set(field.getKey(), field.getValue());
                    }
                    JsonNode converted = toRule(kind, merged);
                    next.set(found, converted);
                    result = toUi(kind, app, req, converted, id(kind, app, found, converted));
                }
            }
            validateAll(kind, next);
            String updated = mapper.writeValueAsString(next);
            if (!config.publishConfigCas(dataId, group, updated, version)) {
                res.getWriter().write(mapper.writeValueAsString(Result.ofFail(409, "Nacos publish conflict or failure; reload")));
                return false;
            }
            res.setHeader("X-Nacos-Version", digest(updated));
            res.setHeader("X-Nacos-Application", app);
            res.setHeader("X-Nacos-Type", kind);
            res.setHeader("X-Nacos-Client-State", "unverified");
            res.getWriter().write(mapper.writeValueAsString(Result.ofSuccess(result)
                    .setMsg("Persisted to Nacos; client loading is not yet verified")));
        } catch (Exception failure) {
            int code = failure instanceof IllegalArgumentException ? 400 : 503;
            String message = code == 400 ? "Invalid rule request or rule data" : "Nacos rule operation unavailable";
            res.getWriter().write(mapper.writeValueAsString(Result.ofFail(code, message)));
        }
        return false;
    }

    private boolean allowed(String path, String method) {
        if ("GET".equals(method)) return path.equals("/v1/flow/rules") || path.equals("/degrade/rules.json")
                || path.equals("/gateway/flow/list.json") || path.equals("/gateway/api/list.json");
        if ("POST".equals(method)) return path.equals("/v1/flow/rule") || path.equals("/degrade/rule")
                || path.matches("/gateway/(flow|api)/(new|save|delete)\\.json");
        if ("PUT".equals(method)) return path.equals("/v1/flow/save.json") || path.matches("/degrade/rule/[0-9]+");
        if ("DELETE".equals(method)) return path.equals("/v1/flow/delete.json") || path.matches("/degrade/rule/[0-9]+");
        return false;
    }

    private String kind(String path) {
        if (path.startsWith("/v1/flow/") || path.startsWith("/v2/flow/")) return "flow";
        if (path.startsWith("/degrade/")) return "degrade";
        if (path.startsWith("/gateway/flow/")) return "gw-flow";
        if (path.startsWith("/gateway/api/")) return "gw-api-group";
        return null;
    }

    private ObjectNode requestData(HttpServletRequest req) throws Exception {
        ObjectNode input = mapper.createObjectNode();
        for (Map.Entry<String, String[]> entry : req.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1) throw new IllegalArgumentException("Duplicate parameter");
            input.put(entry.getKey(), entry.getValue()[0]);
        }
        if (req.getContentType() != null && req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            InputStream stream = req.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int n;
            while ((n = stream.read(bytes)) != -1) {
                if (out.size() + n > 1048576) throw new IllegalArgumentException("Rule request too large");
                out.write(bytes, 0, n);
            }
            if (out.size() > 0) {
                JsonNode body = mapper.readTree(out.toByteArray());
                if (!body.isObject()) throw new IllegalArgumentException("Body must be an object");
                input.setAll((ObjectNode) body);
            }
        }
        return input;
    }

    private ObjectNode toUi(String kind, String app, HttpServletRequest req, JsonNode rule, long id) throws Exception {
        ObjectNode ui;
        if ("gw-flow".equals(kind)) {
            GatewayFlowRule flow = mapper.treeToValue(rule, GatewayFlowRule.class);
            String ip = first(req.getParameter("ip"), "127.0.0.1");
            String port = first(req.getParameter("port"), "0");
            ui = mapper.valueToTree(GatewayFlowRuleEntity.fromGatewayFlowRule(app, ip, Integer.valueOf(port), flow));
        } else {
            ui = ((ObjectNode) rule).deepCopy();
            ObjectNode defaults = null;
            if ("flow".equals(kind)) {
                FlowRule value = mapper.treeToValue(rule, FlowRule.class);
                if (value.getLimitApp() == null || value.getLimitApp().trim().isEmpty()) value.setLimitApp("default");
                defaults = mapper.valueToTree(FlowRuleEntity.fromFlowRule(app, "127.0.0.1", 0, value));
            } else if ("degrade".equals(kind)) {
                DegradeRule value = mapper.treeToValue(rule, DegradeRule.class);
                if (value.getLimitApp() == null || value.getLimitApp().trim().isEmpty()) value.setLimitApp("default");
                defaults = mapper.valueToTree(DegradeRuleEntity.fromDegradeRule(app, "127.0.0.1", 0, value));
            }
            if (defaults != null) {
                Iterator<Map.Entry<String, JsonNode>> fields = defaults.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!ui.hasNonNull(field.getKey()) && !field.getValue().isNull()
                            && !java.util.Arrays.asList("app", "ip", "port").contains(field.getKey()))
                        ui.set(field.getKey(), field.getValue());
                }
            }
        }
        if ("gw-api-group".equals(kind)) ui = normalizeApi(ui);
        if (("flow".equals(kind) || "degrade".equals(kind))
                && ui.path("limitApp").asText("").trim().isEmpty()) ui.put("limitApp", "default");
        ui.put("id", id); ui.put("app", app);
        if (!ui.hasNonNull("ip")) ui.put("ip", first(req.getParameter("ip"), "127.0.0.1"));
        if (!ui.hasNonNull("port")) ui.put("port", Integer.parseInt(first(req.getParameter("port"), "0")));
        return ui;
    }

    private JsonNode toRule(String kind, ObjectNode input) throws Exception {
        try { return convertRule(kind, input); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid rule", failure); }
    }

    private JsonNode convertRule(String kind, ObjectNode input) throws Exception {
        if ("flow".equals(kind)) {
            FlowRule rule = mapper.treeToValue(input, FlowRule.class);
            if (!validFlow(rule)) throw new IllegalArgumentException("Invalid flow rule");
            return mapper.valueToTree(rule);
        }
        if ("degrade".equals(kind)) {
            DegradeRule rule = mapper.treeToValue(input, DegradeRule.class);
            if (!validDegrade(rule)) throw new IllegalArgumentException("Invalid degrade rule");
            return mapper.valueToTree(rule);
        }
        if ("gw-flow".equals(kind)) {
            GatewayFlowRule rule = mapper.treeToValue(input, GatewayFlowRuleEntity.class).toGatewayFlowRule();
            if (!validGateway(rule)) throw new IllegalArgumentException("Invalid gateway flow rule");
            return mapper.valueToTree(rule);
        }
        ApiDefinition rule = mapper.treeToValue(normalizeApi(input), ApiDefinitionEntity.class).toApiDefinition();
        if (!GatewayApiDefinitionManager.isValidApi(rule)) throw new IllegalArgumentException("Invalid gateway API");
        rule.getPredicateItems().forEach(item -> {
            com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem path =
                    (com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem) item;
            if (path.getPattern() == null || path.getPattern().trim().isEmpty()
                    || path.getMatchStrategy() < 0 || path.getMatchStrategy() > 2) throw new IllegalArgumentException("Invalid strategy");
            if (path.getMatchStrategy() == 2) java.util.regex.Pattern.compile(path.getPattern());
        });
        return mapper.valueToTree(rule);
    }

    private void validateAll(String kind, ArrayNode rules) throws Exception {
        Set<String> names = new HashSet<String>();
        for (JsonNode rule : rules) {
            if (!rule.isObject()) throw new IllegalArgumentException("Rule must be an object");
            if ("gw-flow".equals(kind)) {
                if (!validGateway(mapper.treeToValue(rule, GatewayFlowRule.class)))
                    throw new IllegalArgumentException("Invalid gateway rule");
            } else if ("gw-api-group".equals(kind)) {
                String name = rule.path("apiName").asText("");
                if (name.isEmpty() || !names.add(name)) throw new IllegalArgumentException("Invalid API names");
                toRule(kind, (ObjectNode)rule);
            } else if ("flow".equals(kind)) {
                FlowRule flow = mapper.treeToValue(rule, FlowRule.class);
                if (!validFlow(flow))
                    throw new IllegalArgumentException("Invalid flow rule");
            } else if ("degrade".equals(kind)) {
                if (!validDegrade(mapper.treeToValue(rule, DegradeRule.class)))
                    throw new IllegalArgumentException("Invalid degrade rule");
            }
        }
    }

    private ObjectNode normalizeApi(ObjectNode source) {
        ObjectNode copy = source.deepCopy();
        JsonNode items = copy.get("predicateItems");
        if (items == null || !items.isArray()) throw new IllegalArgumentException("Invalid API predicates");
        for (JsonNode item : items) {
            if (!item.isObject()) throw new IllegalArgumentException("Invalid API predicate");
            JsonNode strategy = item.get("matchStrategy");
            if (strategy == null) ((ObjectNode) item).put("matchStrategy", 0);
            else if (!strategy.isIntegralNumber() || !strategy.canConvertToInt())
                throw new IllegalArgumentException("Invalid API strategy");
        }
        return copy;
    }

    private boolean validFlow(FlowRule rule) {
        return rule != null && Double.isFinite(rule.getCount()) && rule.getGrade() >= 0 && rule.getGrade() <= 1
                && rule.getStrategy() >= 0 && rule.getStrategy() <= 2
                && rule.getControlBehavior() >= 0 && rule.getControlBehavior() <= 3
                && FlowRuleUtil.isValidRule(rule);
    }

    private boolean validDegrade(DegradeRule rule) {
        return rule != null && Double.isFinite(rule.getCount()) && Double.isFinite(rule.getSlowRatioThreshold())
                && DegradeRuleManager.isValidRule(rule);
    }

    private boolean validGateway(GatewayFlowRule rule) {
        if (rule == null || !Double.isFinite(rule.getCount()) || !GatewayRuleManager.isValidRule(rule)
                || rule.getResourceMode() > 1 || rule.getGrade() > 1 || rule.getControlBehavior() > 2) return false;
        com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem item = rule.getParamItem();
        if (item == null) return true;
        if (item.getParseStrategy() > 4) return false;
        if (item.getPattern() != null && !item.getPattern().isEmpty()) {
            if (item.getMatchStrategy() > 2) return false;
            if (item.getMatchStrategy() == 2) java.util.regex.Pattern.compile(item.getPattern());
        }
        return true;
    }

    private static String first(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString();
    }
    private long id(String kind, String app, int index, JsonNode rule) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                (kind + "|" + app + "|" + index + "|" + rule.toString()).getBytes(StandardCharsets.UTF_8));
        long result = ByteBuffer.wrap(hash).getLong() & ((1L << 53) - 1); return result == 0 ? 1 : result;
    }
}
