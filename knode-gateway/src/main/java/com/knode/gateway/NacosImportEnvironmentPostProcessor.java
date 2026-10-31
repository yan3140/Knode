package com.knode.gateway;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Resolve the local alias before Spring Cloud checks the literal Config Data import. */
public final class NacosImportEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER - 1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String alias = environment.getProperty("NACOS_CONFIG_IMPORT");
        if (alias != null && !alias.isBlank() && !environment.containsProperty("spring.config.import")) {
            environment.getPropertySources().addFirst(new MapPropertySource("knodeNacosImport",
                    Map.of("spring.config.import", alias)));
        }
    }
}
