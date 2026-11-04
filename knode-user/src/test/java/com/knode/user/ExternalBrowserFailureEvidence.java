package com.knode.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.io.IOException;
import java.nio.file.*;

/** Rebuilds only bounded, non-secret browser failure facts for the manifest. */
final class ExternalBrowserFailureEvidence {
    private static final Set<String> METHODS=Set.of("GET","POST","PUT","DELETE");
    private static final Set<String> CHECKS=Set.of("me","checkedKeys","userRelation","roleRelation","rootParent");
    private static final Set<String> PATHS=Set.of(
            "/api/blog/auth/login","/api/blog/auth/logout","/api/blog/user/userInfo",
            "/dev-api/api/admin/auth/login","/dev-api/api/admin/auth/logout","/dev-api/api/admin/auth/me",
            "/dev-api/api/admin/auth/routers","/dev-api/api/admin/system/user","/dev-api/api/admin/system/user/list",
            "/dev-api/api/admin/system/user/9007199254740993","/dev-api/api/admin/system/role",
            "/dev-api/api/admin/system/role/list","/dev-api/api/admin/system/role/listAllRole",
            "/dev-api/api/admin/system/role/9007199254740995","/dev-api/api/admin/system/menu",
            "/dev-api/api/admin/system/menu/list","/dev-api/api/admin/system/menu/treeselect",
            "/dev-api/api/admin/system/menu/9007199254740997",
            "/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995");
    private static final Map<String,Set<String>> STEPS=Map.ofEntries(
            Map.entry("connect",Set.of("debugger","page","enable")),
            Map.entry("blog-login",Set.of("navigate","username","password","submit","profile")),
            Map.entry("blog-update",Set.of("input","click","put-response","success-message","reload","reread")),
            Map.entry("blog-logout",Set.of("open","confirm","clear")),
            Map.entry("admin-login",Set.of("navigate","username","password","submit","identity","avatar","menus")),
            Map.entry("admin-logout",Set.of("open","choice","confirm","clear")),
            Map.entry("admin-relogin",Set.of("username","password","submit","identity","menus")),
            Map.entry("admin-user-edit",Set.of("navigate","row","form","role","nickname","save","closed")),
            Map.entry("admin-role-edit",Set.of("navigate","row","tree","expand","checked","select","name","save","closed")),
            Map.entry("admin-menu-edit",Set.of("navigate","row","form","name","save","closed","settle")),
            Map.entry("network-assertions",Set.of("check")));
    private ExternalBrowserFailureEvidence() {}

    static ObjectNode read(Path path,ObjectMapper mapper) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)<1 || Files.size(path)>65536) throw invalid();
        byte[] bytes;
        try (var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {bytes=stream.readNBytes(65537);}
        if (bytes.length<1 || bytes.length>65536) throw invalid();
        JsonNode input=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        return sanitize(input,mapper);
    }
    static ObjectNode sanitize(JsonNode input,ObjectMapper mapper) {
        requireKeys(input,Set.of("failed","stage","step","requests","events","profilePutCode","checks"));
        if (!input.path("failed").isBoolean() || !input.path("failed").asBoolean()
                || !input.path("stage").isTextual() || !input.path("step").isTextual()) throw invalid();
        String stage=input.path("stage").textValue(),step=input.path("step").textValue();
        Set<String> allowed=STEPS.get(stage);
        if (allowed==null || !step.startsWith(stage+"-") || !allowed.contains(step.substring(stage.length()+1))) throw invalid();
        JsonNode code=input.get("profilePutCode");
        if (!(code.isNull() || (code.isInt() && code.intValue()>=0 && code.intValue()<=9999))) throw invalid();
        JsonNode checks=input.get("checks");requireKeys(checks,CHECKS);
        ObjectNode out=mapper.createObjectNode();out.put("failed",true);out.put("stage",stage);out.put("step",step);
        var outChecks=out.putObject("checks");
        for (String name:CHECKS) {
            if (!checks.get(name).isBoolean()) throw invalid();
            outChecks.put(name,checks.get(name).booleanValue());
        }
        if (code.isNull()) out.putNull("profilePutCode");else out.put("profilePutCode",code.intValue());
        copyFacts(input.get("requests"),out.putArray("requests"),false);
        copyFacts(input.get("events"),out.putArray("events"),true);
        return out;
    }
    private static void copyFacts(JsonNode input,com.fasterxml.jackson.databind.node.ArrayNode output,boolean response) {
        if (!input.isArray() || input.size()>120) throw invalid();
        for (JsonNode fact:input) {
            requireKeys(fact,response?Set.of("method","path","status"):Set.of("method","path"));
            if (!fact.get("method").isTextual() || !METHODS.contains(fact.get("method").textValue())
                    || !fact.get("path").isTextual() || !PATHS.contains(fact.get("path").textValue())) throw invalid();
            if (response && (!fact.get("status").isInt() || fact.get("status").intValue()<100 || fact.get("status").intValue()>599)) throw invalid();
            ObjectNode copy=output.addObject();copy.put("method",fact.get("method").textValue());copy.put("path",fact.get("path").textValue());
            if (response) copy.put("status",fact.get("status").intValue());
        }
    }
    private static void requireKeys(JsonNode node,Set<String> expected) {
        if (node==null || !node.isObject() || node.size()!=expected.size()) throw invalid();
        var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw invalid();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid sanitized browser failure evidence");}
}
