package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.entity.UserNotification;
import com.yan.domain.vo.NotificationVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserNotificationMapper extends BaseMapper<UserNotification> {
    Page<NotificationVo> selectInbox(Page<NotificationVo> page, @Param("receiverId") Long receiverId);
    long countUnread(@Param("receiverId") Long receiverId);
    int markRead(@Param("id") Long id, @Param("receiverId") Long receiverId);
    int markAllRead(@Param("receiverId") Long receiverId);
    int deleteByIdAndReceiver(@Param("id") Long id, @Param("receiverId") Long receiverId);
    int insertBatch(@Param("items") List<UserNotification> items);
    int insertBatchIgnore(@Param("items") List<UserNotification> items);
}
