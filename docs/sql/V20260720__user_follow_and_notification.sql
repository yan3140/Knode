CREATE TABLE IF NOT EXISTS yan_user_follow (
    id BIGINT NOT NULL AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_yan_user_follow_pair (follower_id, followee_id),
    KEY idx_yan_user_follow_followee (followee_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系';

CREATE TABLE IF NOT EXISTS yan_user_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    receiver_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    article_id BIGINT NULL,
    type VARCHAR(32) NOT NULL,
    content VARCHAR(500) NULL,
    is_read TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_yan_user_notification_inbox (receiver_id, is_read, id),
    KEY idx_yan_user_notification_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户站内通知收件箱';
