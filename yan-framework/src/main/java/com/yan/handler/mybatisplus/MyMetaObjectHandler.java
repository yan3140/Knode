package com.yan.handler.mybatisplus;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.yan.utils.SecurityUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyMetaObjectHandler.class);
    private static final Long SYSTEM_USER_ID = 0L;

    @Override
    public void insertFill(MetaObject metaObject) {
        Long userId = currentUserId();
        this.setFieldValByName("createTime", new Date(), metaObject);
        this.setFieldValByName("createBy", userId, metaObject);
        this.setFieldValByName("updateTime", new Date(), metaObject);
        this.setFieldValByName("updateBy", userId, metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Long userId = currentUserId();
        this.setFieldValByName("updateTime", new Date(), metaObject);
        this.setFieldValByName("updateBy", userId, metaObject);
    }

    private Long currentUserId() {
        try {
            Long userId = SecurityUtils.getUserId();
            return userId == null ? SYSTEM_USER_ID : userId;
        } catch (RuntimeException noSecurityContext) {
            LOGGER.debug("event=mybatis_fill_system_user reason={}", noSecurityContext.getClass().getSimpleName());
            return SYSTEM_USER_ID;
        }
    }
}
