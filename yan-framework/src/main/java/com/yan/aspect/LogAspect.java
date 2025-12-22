package com.yan.aspect;


import com.alibaba.fastjson2.JSON;
import com.yan.annotation.SystemLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Aspect
@Slf4j
public class LogAspect {

    @Pointcut("@annotation(com.yan.annotation.SystemLog)")
    public void pt() {

    }

    @Around("pt()")
    public Object printLog(ProceedingJoinPoint joinPoint) throws Throwable {
        Object proceed;
        try {
            handleBefore(joinPoint);
            proceed = joinPoint.proceed();
            handleAfter(proceed);
        } finally {
            // 结束后换行
            log.info("======End======" + System.lineSeparator());
        }
        return proceed;
    }

    private void handleAfter(Object proceed) {
        // 打印出参
        log.info("Response     : {}", JSON.toJSONString(proceed));
    }

    private void handleBefore(JoinPoint joinPoint) {

        ServletRequestAttributes requestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = requestAttributes.getRequest();

        //获取被增强方法上的注解对象
        SystemLog systemLog = getSystemLog(joinPoint);
        log.info("======Start======");
        // 打印请求 URL
        log.info("URL          : {}", request.getRequestURL());
        // 打印描述信息
        log.info("BusinessName : {}", systemLog.businessName());
        // 打印 Http method
        log.info("HTTP Method  : {}", request.getMethod());
        // 打印调用 controller 的全路径以及执行方法
        log.info("Class Method : {}.{}", joinPoint.getSignature().getDeclaringTypeName(), joinPoint.getSignature().getName());
        // 打印请求的 IP
        log.info("IP           : {}", request.getRemoteHost());
        // 打印请求入参
        log.info("Request Args : {}", JSON.toJSONString(joinPoint.getArgs()));
    }

    private SystemLog getSystemLog(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod().getAnnotation(SystemLog.class);
    }

}
