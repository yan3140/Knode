package com.yan.reliabletask.redisstream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReliableTaskRedisStreamProperties.class)
public class ReliableTaskRedisStreamConfig {
}
