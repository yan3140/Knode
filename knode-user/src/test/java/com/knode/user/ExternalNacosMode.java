package com.knode.user;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Exclusively owned Nacos inputs. Every mutation is journaled before it is attempted. */
final class ExternalNacosMode implements AutoCloseable {
    static final String RELAY="knode-user.relay.json", FLOW="knode-user.flow.json";
    static final String RULE="[{\"resource\":\"HTTP GET /api/blog/user/userInfo\",\"grade\":1,\"count\":0,\"limitApp\":\"default\",\"strategy\":0,\"controlBehavior\":0}]";
    private final ExternalNacosGuard.Inputs inputs;
    private final Path cache;
    private final ObjectMapper json=new ObjectMapper();
    private final Runnable persist;
    private final List<Map<String,Object>> journal=new ArrayList<>();
    private final Map<String,String> original=new LinkedHashMap<>();
    private ConfigService config,otherConfig;
    private NamingService naming,otherNaming;
    private final Map<String,String> previousProperties=new HashMap<>();

    ExternalNacosMode(ExternalNacosGuard.Inputs inputs,Path cache,Runnable persist) {
        this.inputs=inputs;this.cache=cache;this.persist=persist;
    }
    Map<String,Object> manifest() {
        var result=new LinkedHashMap<String,Object>();
        result.put("address",inputs.address());result.put("namespace",inputs.namespace());
        result.put("otherNamespace",inputs.otherNamespace());result.put("discoveryGroup",inputs.discoveryGroup());
        result.put("configGroup",inputs.configGroup());result.put("governanceGroup",inputs.governanceGroup());
        result.put("cache",cache.toAbsolutePath().toString());result.put("changes",List.copyOf(journal));
        return result;
    }
    private Properties properties(String namespace) {
        var p=new Properties();p.put(PropertyKeyConst.SERVER_ADDR,inputs.address());p.put(PropertyKeyConst.NAMESPACE,namespace);
        p.put(PropertyKeyConst.USERNAME,inputs.username());p.put(PropertyKeyConst.PASSWORD,inputs.password());return p;
    }
    void connect() throws Exception {
        // These properties are set before constructing any SDK client. The controller invokes this test in a dedicated fork.
        Map<String,String> properties=Map.of("JM.SNAPSHOT.PATH",cache.resolve("test").toString(),
                "JM.LOG.PATH",cache.resolve("test-logs").toString(),"nacos.cache.data.init.snapshot","false");
        properties.forEach((key,value) -> {previousProperties.put(key,System.getProperty(key));System.setProperty(key,value);});
        try {
            config=NacosFactory.createConfigService(properties(inputs.namespace()));
            otherConfig=NacosFactory.createConfigService(properties(inputs.otherNamespace()));
            naming=NacosFactory.createNamingService(properties(inputs.namespace()));
            otherNaming=NacosFactory.createNamingService(properties(inputs.otherNamespace()));
        } catch (Exception failure) {throw new IllegalStateException("Nacos SDK startup failed");}
    }
    private String read(ConfigService client,String id,String group) throws Exception {
        try {return client.getConfig(id,group,5000);}
        catch (Exception failure) {throw new IllegalStateException("Nacos config read failed for "+id);}
    }
    static int relayValue(String raw) throws Exception {
        if (raw==null || raw.length()>1024) throw new IllegalArgumentException("Explicit relay JSON required");
        var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode node=mapper.readTree(raw);
        if (node==null || !node.isObject() || node.size()!=1 || !node.path("sessionEventRelayBatchSize").isInt())
            throw new IllegalArgumentException("Relay JSON must contain only the bounded batch size");
        int value=node.path("sessionEventRelayBatchSize").intValue();
        if (value<1 || value>100) throw new IllegalArgumentException("Relay batch out of range");
        return value;
    }
    static void emptyRules(String raw) throws Exception {
        if (raw==null || raw.length()>1024) throw new IllegalArgumentException("Explicit empty rule array required");
        JsonNode node=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        if (node==null || !node.isArray() || !node.isEmpty()) throw new IllegalArgumentException("Only empty initial rules are accepted");
    }
    private static String digest(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
    void preflight() throws Exception {
        String route=read(config,"knode-gateway.yaml",inputs.configGroup());
        String expected=Files.readString(Path.of("..","deploy","microservices","nacos","knode-gateway.yaml"),StandardCharsets.UTF_8);
        if (route==null || !route.replace("\r\n","\n").equals(expected.replace("\r\n","\n")))
            throw new IllegalStateException("Gateway route Data ID does not match the published template");
        note("read","knode-gateway.yaml",inputs.configGroup(),null,digest(route),"verified");
        for (String id:List.of("knode-gateway.gw-flow.json","knode-gateway.gw-api-group.json",FLOW,"knode-user.degrade.json")) {
            String raw=read(config,id,inputs.governanceGroup());emptyRules(raw);
            note("read",id,inputs.governanceGroup(),null,digest(raw),"verified");
            if (id.equals(FLOW)) original.put(FLOW,raw);
        }
        String relay=read(config,RELAY,inputs.configGroup());relayValue(relay);original.put(RELAY,relay);
        note("read",RELAY,inputs.configGroup(),null,relay,"verified");
        String other=read(otherConfig,RELAY,inputs.configGroup());relayValue(other);
        original.put("otherRelay",other);
        note("read-other",RELAY,inputs.configGroup(),null,other,"verified");
    }
    private void note(String action,String id,String group,String before,String after,String state) {
        var entry=new LinkedHashMap<String,Object>();entry.put("action",action);entry.put("dataId",id);
        entry.put("group",group);entry.put("namespace",action.contains("other")?inputs.otherNamespace():inputs.namespace());
        entry.put("before",before);entry.put("after",after);entry.put("state",state);
        journal.add(entry);persist.run();
    }
    void publish(String id,String value) throws Exception {
        if (!id.equals(RELAY) && !id.equals(FLOW)) throw new IllegalArgumentException("Data ID not authorized");
        String group=id.equals(RELAY)?inputs.configGroup():inputs.governanceGroup();
        String before=read(config,id,group);
        boolean knownBefore=Objects.equals(before,original.get(id)) || journal.stream().anyMatch(e ->
                id.equals(e.get("dataId")) && "publish".equals(e.get("action")) && Objects.equals(before,e.get("after")));
        boolean allowedValue=id.equals(RELAY)
                ? List.of("{\"sessionEventRelayBatchSize\":0}","{\"sessionEventRelayBatchSize\":2}","{\"sessionEventRelayBatchSize\":3}").contains(value)
                : List.of(RULE,"[]","{\"invalid\":true}").contains(value);
        if (!original.containsKey(id) || before==null || !knownBefore || !allowedValue)
            throw new IllegalStateException("Unexpected config; refusing publication for "+id);
        note("publish",id,group,before,value,"planned");
        try {if (!config.publishConfig(id,group,value)) throw new IllegalStateException("Nacos publish refused");}
        catch (Exception failure) {throw new IllegalStateException("Nacos publish failed for "+id);}
        if (!value.equals(read(config,id,group))) throw new IllegalStateException("Nacos publish readback mismatch");
        note("publish",id,group,before,value,"verified");
    }
    void publishOtherInvalidRelay() throws Exception {
        String before=read(otherConfig,RELAY,inputs.configGroup());
        if (!original.containsKey("otherRelay") || before==null || !Objects.equals(before,original.get("otherRelay")))
            throw new IllegalStateException("Other namespace changed before test");
        String value="{\"sessionEventRelayBatchSize\":0}";
        note("publish-other",RELAY,inputs.configGroup(),before,value,"planned");
        try {if (!otherConfig.publishConfig(RELAY,inputs.configGroup(),value)) throw new IllegalStateException("Publish refused");}
        catch (Exception failure) {throw new IllegalStateException("Other namespace publish failed");}
        if (!value.equals(read(otherConfig,RELAY,inputs.configGroup()))) throw new IllegalStateException("Other namespace readback mismatch");
        note("publish-other",RELAY,inputs.configGroup(),before,value,"verified");
    }
    void restoreOther() throws Exception {
        String before=original.get("otherRelay");
        if (before==null) return;
        String current=read(otherConfig,RELAY,inputs.configGroup());
        if (!Objects.equals(before,current)) {
            boolean known=journal.stream().anyMatch(e -> "publish-other".equals(e.get("action")) && Objects.equals(current,e.get("after")));
            if (!known) {
                note("restore-other",RELAY,inputs.configGroup(),null,null,"refused-unexpected-value");
                throw new IllegalStateException("Unexpected third-party other namespace config; refusing restore");
            }
            note("restore-other",RELAY,inputs.configGroup(),current,before,"planned");
            try {if (!otherConfig.publishConfig(RELAY,inputs.configGroup(),before)) throw new IllegalStateException("Restore refused");}
            catch (Exception failure) {throw new IllegalStateException("Other namespace restore failed");}
            if (!before.equals(read(otherConfig,RELAY,inputs.configGroup()))) throw new IllegalStateException("Other namespace restore readback mismatch");
        }
        note("restore-other",RELAY,inputs.configGroup(),current,before,"verified");
    }
    void assertOtherUnchanged() throws Exception {
        if (!Objects.equals(original.get("otherRelay"),read(otherConfig,RELAY,inputs.configGroup())))
            throw new IllegalStateException("Other namespace relay changed");
        note("read-other",RELAY,inputs.configGroup(),original.get("otherRelay"),original.get("otherRelay"),"unchanged");
    }
    void assertEmptyGroup() throws Exception {
        try {
            if (!naming.getAllInstances("knode-user",inputs.discoveryGroup()).isEmpty())
                throw new IllegalStateException("Discovery group must start empty");
        } catch (com.alibaba.nacos.api.exception.NacosException failure) {
            throw new IllegalStateException("Nacos initial discovery query failed");
        }
    }
    boolean registered(int port) throws Exception {
        try {return naming.getAllInstances("knode-user",inputs.discoveryGroup()).stream().anyMatch(i -> i.getPort()==port && "127.0.0.1".equals(i.getIp()));}
        catch (Exception failure) {throw new IllegalStateException("Nacos discovery query failed");}
    }
    void assertNoWrongDiscovery(int port) throws Exception {
        try {
            if (naming.getAllInstances("knode-user","DEFAULT_GROUP").stream().anyMatch(i -> i.getPort()==port)
                    || otherNaming.getAllInstances("knode-user",inputs.discoveryGroup()).stream().anyMatch(i -> i.getPort()==port))
                throw new IllegalStateException("User registration leaked to wrong group or namespace");
        } catch (com.alibaba.nacos.api.exception.NacosException failure) {
            throw new IllegalStateException("Nacos isolated discovery query failed");
        }
    }
    void restore() throws Exception {
        int failures=0;
        for (String id:List.of(RELAY,FLOW)) {
            try {
                String before=original.get(id);if (before==null) continue;
                String group=id.equals(RELAY)?inputs.configGroup():inputs.governanceGroup();
                String current=read(config,id,group);
                if (!Objects.equals(before,current)) {
                    boolean known=journal.stream().anyMatch(e -> id.equals(e.get("dataId")) && Objects.equals(current,e.get("after"))
                            && "publish".equals(e.get("action")));
                    if (!known) {
                        note("restore",id,group,null,null,"refused-unexpected-value");
                        throw new IllegalStateException("Unexpected third-party config; refusing restore for "+id);
                    }
                    note("restore",id,group,current,before,"planned");
                    if (!config.publishConfig(id,group,before)) throw new IllegalStateException("Restore refused");
                    if (!before.equals(read(config,id,group))) throw new IllegalStateException("Nacos restore readback mismatch");
                }
                note("restore",id,group,current,before,"verified");
            } catch (Exception failure) {failures++;}
        }
        try {restoreOther();} catch (Exception failure) {failures++;}
        try {if (original.containsKey("otherRelay")) assertOtherUnchanged();} catch (Exception failure) {failures++;}
        if (failures>0) throw new IllegalStateException("Nacos restore failed; inspect manifest; count="+failures);
    }
    @Override public void close() {
        int failures=0;
        for (var client:List.of(Optional.ofNullable(config),Optional.ofNullable(otherConfig))) {
            if (client.isPresent()) try {client.get().shutDown();} catch (Exception failure) {failures++;}
        }
        for (var client:List.of(Optional.ofNullable(naming),Optional.ofNullable(otherNaming))) {
            if (client.isPresent()) try {client.get().shutDown();} catch (Exception failure) {failures++;}
        }
        previousProperties.forEach((key,value) -> {if (value==null) System.clearProperty(key); else System.setProperty(key,value);});
        previousProperties.clear();
        if (failures>0) throw new IllegalStateException("Nacos client shutdown failed; count="+failures);
    }
}
