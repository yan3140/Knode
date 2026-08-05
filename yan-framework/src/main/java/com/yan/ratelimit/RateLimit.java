package com.yan.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimits.class)
public @interface RateLimit {
    String name();

    RateLimitTarget target() default RateLimitTarget.IP;

    String paramName() default "";

    int limit();

    int windowSeconds();

    String message() default "请求过于频繁，请稍后再试";
}
