package com.knode.gateway;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The real Nacos discovery adapter queries the group bound from Gateway configuration. */
class GatewayNacosDiscoveryGroupTest {
    private ApplicationContextRunner runner() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("gatewayYaml", new ClassPathResource("application.yml")).get(0);
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(yaml))
                .withPropertyValues("NACOS_GROUP=VERIFY_CONFIG", "NACOS_NAMESPACE=verify-local");
    }

    @Test void defaultDiscoveryGroupRemainsIndependentFromConfigGroup() throws Exception {
        runner().run(context -> {
            var environment = context.getEnvironment();
            var properties = Binder.get(environment).bind("spring.cloud.nacos.discovery",
                    Bindable.of(NacosDiscoveryProperties.class)).orElseThrow(IllegalStateException::new);
            assertThat(properties.getGroup()).isEqualTo("DEFAULT_GROUP");
            assertThat(properties.getNamespace()).isEqualTo("verify-local");
            assertThat(environment.getProperty("spring.cloud.nacos.config.group")).isEqualTo("VERIFY_CONFIG");
        });
    }

    @Test void explicitSpringDiscoveryPropertyStillTakesPrecedence() throws Exception {
        runner().withPropertyValues("NACOS_DISCOVERY_GROUP=ALIAS_GROUP",
                "spring.cloud.nacos.discovery.group=EXPLICIT_GROUP").run(context -> {
            var properties=Binder.get(context.getEnvironment()).bind("spring.cloud.nacos.discovery",
                    Bindable.of(NacosDiscoveryProperties.class)).orElseThrow(IllegalStateException::new);
            assertThat(properties.getGroup()).isEqualTo("EXPLICIT_GROUP");
        });
    }

    @Test void actualDiscoveryAdapterUsesExplicitGroupAndKeepsGroupsIsolated() throws Exception {
        var naming=mock(NamingService.class);
        var manager=mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(naming);
        var user=new Instance(); user.setIp("127.0.0.1"); user.setPort(18090); user.setEnabled(true); user.setHealthy(true);
        when(naming.selectInstances("knode-user","VERIFY_DISCOVERY",true)).thenReturn(List.of(user));
        when(naming.selectInstances("knode-user","OTHER_GROUP",true)).thenReturn(List.of());
        runner().withPropertyValues("NACOS_DISCOVERY_GROUP=VERIFY_DISCOVERY").run(context -> {
            var environment=context.getEnvironment();
            var properties=Binder.get(environment).bind("spring.cloud.nacos.discovery",
                    Bindable.of(NacosDiscoveryProperties.class)).orElseThrow(IllegalStateException::new);
            assertThat(properties.getGroup()).isEqualTo("VERIFY_DISCOVERY");
            assertThat(environment.getProperty("spring.cloud.nacos.config.group")).isEqualTo("VERIFY_CONFIG");
            var instances=new NacosServiceDiscovery(properties,manager).getInstances("knode-user");
            assertThat(instances).hasSize(1);
            assertThat(instances.get(0).getHost()).isEqualTo("127.0.0.1");
            assertThat(instances.get(0).getPort()).isEqualTo(18090);
        });
        runner().withPropertyValues("NACOS_DISCOVERY_GROUP=OTHER_GROUP").run(context -> {
            var properties=Binder.get(context.getEnvironment()).bind("spring.cloud.nacos.discovery",
                    Bindable.of(NacosDiscoveryProperties.class)).orElseThrow(IllegalStateException::new);
            assertThat(new NacosServiceDiscovery(properties,manager).getInstances("knode-user")).isEmpty();
        });
        verify(naming).selectInstances("knode-user","VERIFY_DISCOVERY",true);
        verify(naming).selectInstances("knode-user","OTHER_GROUP",true);
    }
}
