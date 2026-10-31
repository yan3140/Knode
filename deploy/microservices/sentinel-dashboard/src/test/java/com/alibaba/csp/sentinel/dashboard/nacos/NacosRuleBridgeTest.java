package com.alibaba.csp.sentinel.dashboard.nacos;

import com.alibaba.csp.sentinel.dashboard.auth.AuthService;
import com.alibaba.csp.sentinel.dashboard.auth.DefaultAuthorizationInterceptor;
import com.alibaba.csp.sentinel.dashboard.controller.FlowControllerV1;
import com.alibaba.csp.sentinel.dashboard.controller.v2.FlowControllerV2;
import com.alibaba.csp.sentinel.dashboard.controller.DegradeController;
import com.alibaba.csp.sentinel.dashboard.controller.gateway.GatewayFlowRuleController;
import com.alibaba.csp.sentinel.dashboard.controller.gateway.GatewayApiController;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NacosRuleBridgeTest {
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, String> store = new HashMap<>();
    private ConfigService nacos;
    private AuthService<HttpServletRequest> auth;
    private AuthService.AuthUser user;
    private MockMvc mvc;
    private boolean casFailure;
    private boolean unavailable;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        nacos = mock(ConfigService.class);
        auth = mock(AuthService.class);
        user = mock(AuthService.AuthUser.class);
        when(auth.getAuthUser(any())).thenReturn(user);
        when(user.authTarget(eq("demo"), any())).thenReturn(true);
        when(nacos.getConfig(anyString(), eq("KNODE_SENTINEL"), anyLong())).thenAnswer(call -> {
            if (unavailable) throw new IllegalStateException("offline");
            return store.get(call.getArgument(0));
        });
        when(nacos.publishConfigCas(anyString(), eq("KNODE_SENTINEL"), anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(0), value = call.getArgument(2), version = call.getArgument(3);
            if (unavailable) throw new IllegalStateException("offline");
            if (casFailure || !md5(store.get(id)).equals(version)) return false;
            store.put(id, value); return true;
        });
        for (String kind : new String[]{"flow", "degrade", "gw-flow", "gw-api-group"})
            store.put("demo." + kind + ".json", "[]");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("dashboardNacosConfig", nacos);
        NacosRuleBridge bridge = new NacosRuleBridge(factory.getBeanProvider(ConfigService.class), json, "KNODE_SENTINEL");
        mvc = standaloneSetup(new FlowControllerV1(), new FlowControllerV2(), new DegradeController(),
                new GatewayFlowRuleController(), new GatewayApiController())
                .addInterceptors(new DefaultAuthorizationInterceptor(auth), bridge).build();
    }

    @Test void fourNativeRoutesPersistAndDeleteWithCompareAndSet() throws Exception {
        exercise("flow", "/v1/flow/rules", "/v1/flow/rule", "/v1/flow/save.json", "/v1/flow/delete.json",
            "{\"app\":\"demo\",\"resource\":\"r\",\"limitApp\":\"default\",\"grade\":1,\"count\":4,\"strategy\":0,\"controlBehavior\":0}", "count", 7);
        exercise("degrade", "/degrade/rules.json", "/degrade/rule", "/degrade/rule/", "/degrade/rule/",
            "{\"app\":\"demo\",\"resource\":\"r\",\"limitApp\":\"default\",\"grade\":1,\"count\":0.5,\"timeWindow\":10,\"minRequestAmount\":5,\"statIntervalMs\":1000}", "timeWindow", 20);
        exercise("gw-flow", "/gateway/flow/list.json", "/gateway/flow/new.json", "/gateway/flow/save.json", "/gateway/flow/delete.json",
            "{\"app\":\"demo\",\"resource\":\"route-a\",\"resourceMode\":0,\"grade\":1,\"count\":4,\"interval\":1,\"intervalUnit\":0,\"controlBehavior\":0,\"burst\":0,\"maxQueueingTimeoutMs\":0}", "count", 7);
        exercise("gw-api-group", "/gateway/api/list.json", "/gateway/api/new.json", "/gateway/api/save.json", "/gateway/api/delete.json",
            "{\"app\":\"demo\",\"apiName\":\"group-a\",\"predicateItems\":[{\"pattern\":\"/api/**\",\"matchStrategy\":1}]}", "apiName", "group-b");
    }

    private void exercise(String kind, String list, String create, String update, String remove,
            String body, String field, Object updatedValue) throws Exception {
        MvcResult empty = perform(get(list).param("app", "demo").param("ip", "127.0.0.1").param("port", "8719"));
        assertEquals(0, content(empty).path("code").asInt(), kind);
        String initial = empty.getResponse().getHeader("X-Nacos-Version");
        assertEquals(md5("[]"), initial);
        MvcResult made = perform(post(create).param("app", "demo").contentType(MediaType.APPLICATION_JSON)
                .header("X-Nacos-Version", initial).content(body));
        assertEquals(0, content(made).path("code").asInt(), kind + " create: " + made.getResponse().getContentAsString());
        assertEquals("unverified", made.getResponse().getHeader("X-Nacos-Client-State"));
        MvcResult read = perform(get(list).param("app", "demo").param("ip", "127.0.0.1").param("port", "8719"));
        JsonNode row = content(read).path("data").get(0);
        assertNotNull(row, kind);
        long id = row.path("id").asLong();
        String version = read.getResponse().getHeader("X-Nacos-Version");
        String editBody = "{\"id\":" + id + ",\"app\":\"demo\",\"" + field + "\":"
                + json.writeValueAsString(updatedValue) + "}";
        MockHttpServletRequestBuilder edit;
        if (kind.equals("flow")) edit = put(update).param("id", Long.toString(id)).param(field, String.valueOf(updatedValue));
        else if (kind.equals("degrade")) edit = put(update + id).contentType(MediaType.APPLICATION_JSON).content(editBody);
        else edit = post(update).contentType(MediaType.APPLICATION_JSON).content(editBody);
        MvcResult changed = perform(edit.param("app", "demo").header("X-Nacos-Version", version));
        assertEquals(0, content(changed).path("code").asInt(), kind + " edit: " + changed.getResponse().getContentAsString());
        JsonNode persisted = json.readTree(store.get("demo." + kind + ".json")).get(0).path(field);
        if (updatedValue instanceof Number) assertEquals(((Number) updatedValue).doubleValue(), persisted.asDouble(), kind);
        else assertEquals(String.valueOf(updatedValue), persisted.asText(), kind);
        MvcResult again = perform(get(list).param("app", "demo").param("ip", "127.0.0.1").param("port", "8719"));
        long newId = content(again).path("data").get(0).path("id").asLong();
        String latest = again.getResponse().getHeader("X-Nacos-Version");
        MockHttpServletRequestBuilder del = kind.equals("degrade") ? delete(remove + newId)
                : kind.equals("flow") ? delete(remove).param("id", Long.toString(newId))
                : post(remove).param("id", Long.toString(newId));
        MvcResult deleted = perform(del.param("app", "demo").header("X-Nacos-Version", latest));
        assertEquals(0, content(deleted).path("code").asInt(), kind + " delete: " + deleted.getResponse().getContentAsString());
        assertEquals(0, json.readTree(store.get("demo." + kind + ".json")).size(), kind);
    }

    @Test void conflictsErrorsAndAuthorizationNeverReportSuccess() throws Exception {
        String list = "/gateway/api/list.json", create = "/gateway/api/new.json";
        String body = "{\"app\":\"demo\",\"apiName\":\"a\",\"predicateItems\":[{\"pattern\":\"/a\",\"matchStrategy\":0}]}";
        MvcResult read = perform(get(list).param("app","demo"));
        String version = read.getResponse().getHeader("X-Nacos-Version");
        assertNotEquals(0, content(perform(post(create).param("app","demo").contentType(MediaType.APPLICATION_JSON).content(body))).path("code").asInt());
        casFailure = true;
        assertEquals(409, content(perform(post(create).param("app","demo").header("X-Nacos-Version", version).contentType(MediaType.APPLICATION_JSON).content(body))).path("code").asInt());
        assertEquals("[]", store.get("demo.gw-api-group.json"));
        casFailure = false;
        store.put("demo.gw-api-group.json", "[ ]");
        assertEquals(409, content(perform(post(create).param("app","demo").header("X-Nacos-Version", version).contentType(MediaType.APPLICATION_JSON).content(body))).path("code").asInt());
        String different = body.replace("\"demo\"", "\"other\"");
        assertNotEquals(0, content(perform(post(create).param("app","demo").header("X-Nacos-Version", md5("[ ]")).contentType(MediaType.APPLICATION_JSON).content(different))).path("code").asInt());
        store.remove("demo.gw-api-group.json");
        assertNotEquals(0, content(perform(get(list).param("app","demo"))).path("code").asInt());
        store.put("demo.gw-api-group.json", "{invalid");
        assertNotEquals(0, content(perform(get(list).param("app","demo"))).path("code").asInt());
        assertEquals("{invalid", store.get("demo.gw-api-group.json"));
        store.put("demo.gw-api-group.json", "[ ]");
        assertNotEquals(0, content(perform(get("/v2/flow/rules").param("app","demo"))).path("code").asInt());
        MvcResult encoded = perform(get(java.net.URI.create("/gateway/%61pi/list.json")).param("app","demo"));
        assertEquals(0, content(encoded).path("code").asInt());
        assertNotNull(encoded.getResponse().getHeader("X-Nacos-Version"));
        unavailable = true;
        assertNotEquals(0, content(perform(get(list).param("app","demo"))).path("code").asInt());
        unavailable = false;
        when(user.authTarget(eq("demo"), any())).thenReturn(false);
        int before = store.size();
        assertNotEquals(0, content(perform(post(create).param("app","demo").header("X-Nacos-Version", md5("[ ]")).contentType(MediaType.APPLICATION_JSON).content(body))).path("code").asInt());
        assertEquals(before, store.size());
        assertEquals("[ ]", store.get("demo.gw-api-group.json"));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn(); }
    private JsonNode content(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private static String md5(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
}
