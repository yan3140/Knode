package com.knode.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class UserApplication {
    public static void main(String[] args) { SpringApplication.run(UserApplication.class, args); }
}
