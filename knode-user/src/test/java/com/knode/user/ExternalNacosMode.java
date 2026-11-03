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
    private final long readbackTimeoutMillis,readbackIntervalMillis;
    private final List<Map<String,Object>> journal=new ArrayList<>();
    private final Map<String,String> original=new LinkedHashMap<>();
    private ConfigService config,otherConfig;
    private NamingService naming,otherNaming;
    private final Map<String,String> previousProperties=new HashMap<>();

    ExternalNacosMode(ExternalNacosGuard.Inputs inputs,Path cache,Runnable persist) {
        this(inputs,cache,persist,10000,200);
    }
    ExternalNacosMode(ExternalNacosGuard.Inputs inputs,Path cache,Runnable persist,long timeoutMillis,long intervalMillis) {
        if (timeoutMillis<1 || intervalMillis<1) throw new IllegalArgumentException("Positive readback bounds required");
        this.inputs=inputs;this.cache=cache;this.persist=persist;
        this.readbackTimeoutMillis=timeoutMillis;this.readbackIntervalMillis=intervalMillis;
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
    private String read(ConfigService client,String id,String group) throws Exception {return read(client,id,group,5000);}
    private String read(ConfigService client,String id,String group,long timeoutMillis) throws Exception {
        try {return client.getConfig(id,group,timeoutMillis);}
        catch (Exception failure) {throw new IllegalStateException("Nacos config read failed for "+id);}
    }
    private void awaitReadback(ConfigService client,String action,String id,String group,String before,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readbackTimeoutMillis);
        while (true) {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) {
                note(action,id,group,before,expected,"readback-timeout");
                throw new IllegalStateException("Nacos readback timed out for "+action+" "+id);
            }
            String actual;
            try {actual=read(client,id,group,Math.max(1,Math.min(1500,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));}
            catch (IllegalStateException unavailable) {
                Thread.sleep(Math.max(1,Math.min(readbackIntervalMillis,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
                continue;
            }
            if (Objects.equals(actual,expected)) return;
            if (!Objects.equals(actual,before)) {
                note(action,id,group,before,expected,"readback-unexpected");
                throw new IllegalStateException("Unexpected Nacos readback for "+action+" "+id);
            }
            remaining=deadline-System.nanoTime();
            if (remaining>0) Thread.sleep(Math.max(1,Math.min(readbackIntervalMillis,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
        }
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
    private void settlePending(ConfigService client,String id,String namespace) throws Exception {
        for (int i=journal.size()-1;i>=0;i--) {
            var entry=journal.get(i);
            if (!id.equals(entry.get("dataId")) || !namespace.equals(entry.get("namespace"))) continue;
            String state=(String)entry.get("state"),action=(String)entry.get("action");
            if (!(action.startsWith("publish") || action.startsWith("remove") || action.startsWith("restore"))) continue;
            if ("verified".equals(state)) return;
            if ("planned".equals(state)) {
                String group=(String)entry.get("group"),before=(String)entry.get("before"),after=(String)entry.get("after");
                awaitReadback(client,action,id,group,before,after);
                note(action,id,group,before,after,"verified");
                return;
            }
        }
    }
    private String currentExpected(ConfigService client,String id,String group,String namespace,String originalValue) throws Exception {
        settlePending(client,id,namespace);
        String expected=originalValue,previous=originalValue;
        for (int i=journal.size()-1;i>=0;i--) {
            var entry=journal.get(i);
            if (!id.equals(entry.get("dataId")) || !namespace.equals(entry.get("namespace"))
                    || !"verified".equals(entry.get("state"))) continue;
            String action=(String)entry.get("action");
            if (action.startsWith("publish") || action.startsWith("remove") || action.startsWith("restore")) {
                expected=(String)entry.get("after");previous=(String)entry.get("before");break;
            }
        }
        awaitReadback(client,namespace.equals(inputs.otherNamespace())?"check-other":"check-current",id,group,previous,expected);
        return expected;
    }
    void publish(String id,String value) throws Exception {
        if (!id.equals(RELAY) && !id.equals(FLOW)) throw new IllegalArgumentException("Data ID not authorized");
        String group=id.equals(RELAY)?inputs.configGroup():inputs.governanceGroup();
        if (!original.containsKey(id)) throw new IllegalStateException("Missing original config for "+id);
        String before=currentExpected(config,id,group,inputs.namespace(),original.get(id));
        boolean allowedValue=id.equals(RELAY)
                ? List.of("{\"sessionEventRelayBatchSize\":0}","{\"sessionEventRelayBatchSize\":2}","{\"sessionEventRelayBatchSize\":3}").contains(value)
                : List.of(RULE,"[]","{\"invalid\":true}").contains(value);
        if (!allowedValue) throw new IllegalStateException("Unexpected config; refusing publication for "+id);
        note("publish",id,group,before,value,"planned");
        try {if (!config.publishConfig(id,group,value)) throw new IllegalStateException("Nacos publish refused");}
        catch (Exception failure) {throw new IllegalStateException("Nacos publish failed for "+id);}
        awaitReadback(config,"publish",id,group,before,value);
        note("publish",id,group,before,value,"verified");
    }
    void removeFlow() throws Exception {
        String group=inputs.governanceGroup();
        if (!original.containsKey(FLOW)) throw new IllegalStateException("Missing original flow config");
        String before=currentExpected(config,FLOW,group,inputs.namespace(),original.get(FLOW));
        if (before==null) throw new IllegalStateException("Unexpected flow config; refusing removal");
        emptyRules(before);
        note("remove",FLOW,group,before,null,"planned");
        try {if (!config.removeConfig(FLOW,group)) throw new IllegalStateException("Removal refused");}
        catch (Exception failure) {throw new IllegalStateException("Nacos flow removal failed");}
        awaitReadback(config,"remove",FLOW,group,before,null);
        note("remove",FLOW,group,before,null,"verified");
    }
    void publishOtherInvalidRelay() throws Exception {
        if (!original.containsKey("otherRelay")) throw new IllegalStateException("Missing other namespace config");
        String before=currentExpected(otherConfig,RELAY,inputs.configGroup(),inputs.otherNamespace(),original.get("otherRelay"));
        if (!Objects.equals(before,original.get("otherRelay"))) throw new IllegalStateException("Other namespace changed before test");
        String value="{\"sessionEventRelayBatchSize\":0}";
        note("publish-other",RELAY,inputs.configGroup(),before,value,"planned");
        try {if (!otherConfig.publishConfig(RELAY,inputs.configGroup(),value)) throw new IllegalStateException("Publish refused");}
        catch (Exception failure) {throw new IllegalStateException("Other namespace publish failed");}
        awaitReadback(otherConfig,"publish-other",RELAY,inputs.configGroup(),before,value);
        note("publish-other",RELAY,inputs.configGroup(),before,value,"verified");
    }
    void restoreOther() throws Exception {
        String before=original.get("otherRelay");
        if (before==null) return;
        String current=currentExpected(otherConfig,RELAY,inputs.configGroup(),inputs.otherNamespace(),before);
        if (!Objects.equals(before,current)) {
            note("restore-other",RELAY,inputs.configGroup(),current,before,"planned");
            try {if (!otherConfig.publishConfig(RELAY,inputs.configGroup(),before)) throw new IllegalStateException("Restore refused");}
            catch (Exception failure) {throw new IllegalStateException("Other namespace restore failed");}
            awaitReadback(otherConfig,"restore-other",RELAY,inputs.configGroup(),current,before);
        }
        note("restore-other",RELAY,inputs.configGroup(),current,before,"verified");
    }
    void assertOtherUnchanged() throws Exception {
        if (!Objects.equals(original.get("otherRelay"),
                currentExpected(otherConfig,RELAY,inputs.configGroup(),inputs.otherNamespace(),original.get("otherRelay"))))
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
                String current=currentExpected(config,id,group,inputs.namespace(),before);
                if (!Objects.equals(before,current)) {
                    note("restore",id,group,current,before,"planned");
                    if (!config.publishConfig(id,group,before)) throw new IllegalStateException("Restore refused");
                    awaitReadback(config,"restore",id,group,current,before);
                }
                note("restore",id,group,current,before,"verified");
            } catch (Exception failure) {failures++;}
        }
        boolean otherRestored=true;
        try {restoreOther();} catch (Exception failure) {failures++;otherRestored=false;}
        try {if (otherRestored && original.containsKey("otherRelay")) assertOtherUnchanged();} catch (Exception failure) {failures++;}
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
