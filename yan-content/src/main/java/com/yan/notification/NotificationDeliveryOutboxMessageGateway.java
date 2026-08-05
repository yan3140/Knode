package com.yan.notification;

import com.yan.domain.entity.NotificationDeliveryOutboxEvent;

public interface NotificationDeliveryOutboxMessageGateway {
    void publish(NotificationDeliveryOutboxEvent event);
}
