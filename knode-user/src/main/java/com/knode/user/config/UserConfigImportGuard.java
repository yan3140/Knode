package com.knode.user.config;

import java.util.Locale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject generic Nacos imports before ConfigData can load arbitrary remote properties. */
public final class UserConfigImportGuard implements EnvironmentPostProcessor,Ordered {
    @Override public int getOrder() {return Ordered.HIGHEST_PRECEDENCE;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment env,SpringApplication application) {
        String imports=env.getProperty("spring.config.import","");
        if (imports.toLowerCase(Locale.ROOT).contains("nacos:") || env.getProperty("spring.cloud.nacos.config.enabled",Boolean.class,false))
            throw new IllegalStateException("Use only the fixed User Nacos relay configuration");
        boolean discovery=env.getProperty("USER_NACOS_DISCOVERY_ENABLED",Boolean.class,false)
                || env.getProperty("spring.cloud.nacos.discovery.enabled",Boolean.class,false);
        boolean config=env.getProperty("USER_NACOS_CONFIG_ENABLED",Boolean.class,false);
        boolean governance=env.getProperty("USER_GOVERNANCE_ENABLED",Boolean.class,false)
                || env.getProperty("knode.governance.enabled",Boolean.class,false);
        if (discovery || config || governance) {
            String address=first(env,"USER_NACOS_SERVER_ADDR","NACOS_SERVER_ADDR","spring.cloud.nacos.discovery.server-addr","knode.governance.nacos.server-addr");
            String namespace=first(env,"USER_NACOS_NAMESPACE","NACOS_NAMESPACE","spring.cloud.nacos.discovery.namespace","knode.governance.nacos.namespace");
            if (address==null || namespace==null || "public".equalsIgnoreCase(namespace))
                throw new IllegalStateException("Explicit isolated Nacos address and namespace required");
            if (discovery && first(env,"USER_NACOS_GROUP","NACOS_GROUP","spring.cloud.nacos.discovery.group")==null)
                throw new IllegalStateException("Explicit Nacos discovery group required");
            if (config && first(env,"USER_NACOS_CONFIG_GROUP","USER_NACOS_GROUP","NACOS_GROUP")==null)
                throw new IllegalStateException("Explicit Nacos configuration group required");
            if (governance && first(env,"USER_GOVERNANCE_GROUP","knode.governance.nacos.group")==null)
                throw new IllegalStateException("Explicit Nacos governance group required");
            String user=first(env,"USER_NACOS_USERNAME","NACOS_USERNAME"),password=first(env,"USER_NACOS_PASSWORD","NACOS_PASSWORD");
            if ((user==null)!=(password==null)) throw new IllegalStateException("Provide both Nacos credential values");
        }
    }
    private static String first(ConfigurableEnvironment env,String... names) {
        for (String name:names) {String value=env.getProperty(name);if (value!=null && !value.isBlank()) return value;}
        return null;
    }
}
