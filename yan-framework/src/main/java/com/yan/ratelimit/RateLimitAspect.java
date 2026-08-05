package com.yan.ratelimit;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;

@Aspect
@Component
public class RateLimitAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitAspect.class);
    private static final String KEY_PREFIX = "blog:rate-limit:";
    private static final int TOO_MANY_REQUESTS = 429;

    private final RedisFixedWindowRateLimiter rateLimiter;

    public RateLimitAspect(RedisFixedWindowRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Around("@annotation(com.yan.ratelimit.RateLimit) || @annotation(com.yan.ratelimit.RateLimits)")
    public Object limit(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Collection<RateLimit> limits = AnnotatedElementUtils.findMergedRepeatableAnnotations(method, RateLimit.class);
        for (RateLimit limit : limits) {
            String redisKey = buildKey(limit, request, signature.getParameterNames(), joinPoint.getArgs());
            try {
                if (!rateLimiter.tryAcquire(redisKey, limit.limit(), limit.windowSeconds())) {
                    LOGGER.warn("event=rate_limit_blocked name={} target={} path={} clientIp={}",
                            limit.name(), limit.target(), request.getRequestURI(), clientIp(request));
                    return ResponseResult.errorResult(TOO_MANY_REQUESTS, limit.message());
                }
            } catch (RuntimeException redisFailure) {
                LOGGER.warn("event=rate_limit_fail_open name={} path={}",
                        limit.name(), request.getRequestURI(), redisFailure);
            }
        }
        return joinPoint.proceed();
    }

    private String buildKey(RateLimit limit, HttpServletRequest request, String[] parameterNames, Object[] args) {
        String ip = clientIp(request);
        String user = currentUserId();
        String param = parameterValue(limit.paramName(), request, parameterNames, args);
        String identity = switch (limit.target()) {
            case IP -> "ip:" + ip;
            case USER -> StringUtils.hasText(user) ? "user:" + user : "ip:" + ip;
            case PARAM -> "param:" + param;
            case IP_AND_PARAM -> "ip:" + ip + ":param:" + param;
            case USER_AND_PARAM -> (StringUtils.hasText(user) ? "user:" + user : "ip:" + ip) + ":param:" + param;
        };
        return KEY_PREFIX + limit.name() + ":" + limit.target().name().toLowerCase() + ":" + digest(identity);
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof LoginUser loginUser && loginUser.getUser() != null
                && loginUser.getUser().getId() != null) {
            return loginUser.getUser().getId().toString();
        }
        return principal instanceof String value && !"anonymousUser".equals(value) ? value : "";
    }

    private String parameterValue(String paramName, HttpServletRequest request, String[] parameterNames, Object[] args) {
        if (!StringUtils.hasText(paramName)) {
            return "";
        }
        String fromRequest = request.getParameter(paramName);
        if (StringUtils.hasText(fromRequest)) {
            return fromRequest;
        }
        if (parameterNames != null) {
            for (int index = 0; index < parameterNames.length; index++) {
                if (Objects.equals(parameterNames[index], paramName)) {
                    return String.valueOf(args[index]);
                }
            }
        }
        for (Object arg : args) {
            if (arg == null || isSimpleValue(arg)) {
                continue;
            }
            try {
                Object value = new BeanWrapperImpl(arg).getPropertyValue(paramName);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (RuntimeException ignored) {
                // This argument simply does not expose the requested property.
            }
        }
        return "";
    }

    private boolean isSimpleValue(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum();
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
