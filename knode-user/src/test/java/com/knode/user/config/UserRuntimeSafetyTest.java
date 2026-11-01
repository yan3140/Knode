package com.knode.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class UserRuntimeSafetyTest {
    @Test void forbidsUnboundedRemoteImportsBeforeBootstrap() {
        var guard=new UserConfigImportGuard();var application=new SpringApplication();
        assertDoesNotThrow(() -> guard.postProcessEnvironment(new MockEnvironment(),application));
        assertThrows(IllegalStateException.class,() -> guard.postProcessEnvironment(new MockEnvironment().withProperty("spring.config.import","optional:nacos:other.yaml"),application));
        assertThrows(IllegalStateException.class,() -> guard.postProcessEnvironment(new MockEnvironment().withProperty("spring.cloud.nacos.config.enabled","true"),application));
        assertThrows(IllegalStateException.class,() -> guard.postProcessEnvironment(new MockEnvironment().withProperty("USER_NACOS_DISCOVERY_ENABLED","true"),application));
        assertDoesNotThrow(() -> guard.postProcessEnvironment(new MockEnvironment().withProperty("USER_NACOS_DISCOVERY_ENABLED","true")
                .withProperty("USER_NACOS_SERVER_ADDR","127.0.0.1:9999").withProperty("USER_NACOS_NAMESPACE","verify-test")
                .withProperty("USER_NACOS_GROUP","VERIFY_TEST"),application));
    }
    @Test void environmentDiscoveryAndManagementAreExplicit() {
        assertDoesNotThrow(() -> new UserRuntimeSafety(new MockEnvironment()));
        assertThrows(IllegalStateException.class,() -> new UserRuntimeSafety(new MockEnvironment()
                .withProperty("knode.observability.enabled","true").withProperty("KNODE_AUTH_ENVIRONMENT","test")
                .withProperty("knode.observability.environment","production")));
        assertThrows(IllegalStateException.class,() -> new UserRuntimeSafety(new MockEnvironment()
                .withProperty("spring.cloud.nacos.discovery.enabled","true")));
        assertThrows(IllegalStateException.class,() -> new UserRuntimeSafety(new MockEnvironment()
                .withProperty("management.server.address","0.0.0.0")));
        assertThrows(IllegalStateException.class,() -> new UserRuntimeSafety(new MockEnvironment()
                .withProperty("management.server.port","18090").withProperty("server.port","18090")));
    }

    @Test void discoveryReadinessRequiresThisActualRegistration() throws Exception {
        var env=new MockEnvironment().withProperty("spring.cloud.nacos.discovery.enabled","true").withProperty("spring.application.name","knode-user");
        @SuppressWarnings("unchecked") var managers=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked") var properties=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var manager=org.mockito.Mockito.mock(com.alibaba.cloud.nacos.NacosServiceManager.class);
        var options=org.mockito.Mockito.mock(com.alibaba.cloud.nacos.NacosDiscoveryProperties.class);
        var naming=org.mockito.Mockito.mock(com.alibaba.nacos.api.naming.NamingService.class);
        org.mockito.Mockito.when(managers.getIfAvailable()).thenReturn(manager);
        org.mockito.Mockito.when(properties.getIfAvailable()).thenReturn(options);
        org.mockito.Mockito.when(manager.getNamingService()).thenReturn(naming);
        org.mockito.Mockito.when(naming.getServerStatus()).thenReturn("UP");
        org.mockito.Mockito.when(options.getGroup()).thenReturn("VERIFY");
        org.mockito.Mockito.when(options.getIp()).thenReturn("127.0.0.1");
        org.mockito.Mockito.when(options.getPort()).thenReturn(18090);
        var instance=new com.alibaba.nacos.api.naming.pojo.Instance();instance.setIp("127.0.0.1");instance.setPort(18090);instance.setHealthy(true);instance.setEnabled(true);
        org.mockito.Mockito.when(naming.getAllInstances("knode-user","VERIFY")).thenReturn(java.util.List.of(instance));
        @SuppressWarnings("unchecked") var status=new UserDiscoveryStatus(env,managers,properties);
        assertEquals(org.springframework.boot.actuate.health.Status.UP,status.health().getStatus());
        instance.setPort(18091);
        assertEquals(org.springframework.boot.actuate.health.Status.OUT_OF_SERVICE,status.health().getStatus());
        org.mockito.Mockito.when(naming.getServerStatus()).thenReturn("DOWN");
        assertEquals(org.springframework.boot.actuate.health.Status.OUT_OF_SERVICE,status.health().getStatus());
    }
}
