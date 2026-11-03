package com.knode.user;

import java.net.URI;
import java.util.Map;

/** Inputs for one explicitly owned Nacos integration run; no network access. */
final class ExternalNacosGuard {
    record Inputs(String address, String namespace, String otherNamespace, String discoveryGroup,
                  String configGroup, String governanceGroup, String username, String password) {
        @Override public String toString() {return "Nacos test inputs (credentials redacted)";}
    }
    private ExternalNacosGuard() {}

    static Inputs check(Map<String,String> env) {
        if (!"confirm".equals(env.get("KNODE_IT_NACOS_EXCLUSIVE")))
            throw new IllegalArgumentException("Explicit exclusive Nacos instance confirmation required");
        String address=required(env,"KNODE_IT_NACOS_ADDR");
        try {
            URI uri=URI.create("http://"+address);
            if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1024 || uri.getPort()==8848
                    || uri.getPort()>64535 || uri.getUserInfo()!=null || uri.getQuery()!=null
                    || uri.getFragment()!=null || uri.getPath()==null || !uri.getPath().isEmpty()
                    || !address.equals("127.0.0.1:"+uri.getPort())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Explicit loopback non-default Nacos port with gRPC offset required");
        }
        String namespace=namespace(env,"KNODE_IT_NACOS_NAMESPACE");
        String other=namespace(env,"KNODE_IT_NACOS_OTHER_NAMESPACE");
        if (namespace.equals(other)) throw new IllegalArgumentException("Two distinct isolated namespaces required");
        return new Inputs(address,namespace,other,group(env,"KNODE_IT_NACOS_DISCOVERY_GROUP"),
                group(env,"KNODE_IT_NACOS_CONFIG_GROUP"),group(env,"KNODE_IT_NACOS_GOVERNANCE_GROUP"),
                required(env,"KNODE_IT_NACOS_USERNAME"),required(env,"KNODE_IT_NACOS_PASSWORD"));
    }
    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing explicit "+key);
        return value;
    }
    private static String namespace(Map<String,String> env,String key) {
        String value=required(env,key);
        if (!value.matches("verify-[a-z0-9-]{1,25}")) throw new IllegalArgumentException("Invalid isolated namespace");
        return value;
    }
    private static String group(Map<String,String> env,String key) {
        String value=required(env,key);
        if (!value.matches("VERIFY_[A-Z0-9_]{1,40}")) throw new IllegalArgumentException("Invalid isolated group");
        return value;
    }
}
