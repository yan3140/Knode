-- D11：让评论通知能够准确跳转和在通知页内回复。
ALTER TABLE yan_user_notification
    ADD COLUMN comment_id BIGINT NULL COMMENT '触发通知的评论 ID' AFTER article_id,
    ADD COLUMN root_comment_id BIGINT NULL COMMENT '评论所属根评论 ID，-1 表示根评论' AFTER comment_id,
    ADD KEY idx_yan_user_notification_comment (comment_id);
