package com.yan.notification;

import com.yan.domain.entity.NotificationOutboxEvent;

public interface NotificationFanoutOutboxMessageGateway {
    void publish(NotificationOutboxEvent event);
}
