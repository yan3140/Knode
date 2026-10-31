package com.knode.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class NacosImportEnvironmentPostProcessorTest {
    @Test void aliasIsResolvedEarlyAndExplicitSpringImportWins() {
        var processor = new NacosImportEnvironmentPostProcessor();
        MockEnvironment alias = new MockEnvironment().withProperty("NACOS_CONFIG_IMPORT", "nacos:demo.properties");
        processor.postProcessEnvironment(alias, new SpringApplication());
        assertThat(alias.getProperty("spring.config.import")).isEqualTo("nacos:demo.properties");
        MockEnvironment explicit = new MockEnvironment().withProperty("NACOS_CONFIG_IMPORT", "nacos:alias.properties")
                .withProperty("spring.config.import", "nacos:explicit.properties");
        processor.postProcessEnvironment(explicit, new SpringApplication());
        assertThat(explicit.getProperty("spring.config.import")).isEqualTo("nacos:explicit.properties");
    }
}
