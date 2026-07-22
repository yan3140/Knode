-- Day 5：评论聚合与公开文章查询索引（MySQL 8.0，单次执行）

ALTER TABLE yan_comment
    ADD INDEX idx_comment_root_page (type, root_id, del_flag, article_id, create_time),
    ADD INDEX idx_comment_children (root_id, del_flag, create_time);

ALTER TABLE yan_article
    ADD INDEX idx_article_public_list (status, del_flag, is_top),
    ADD INDEX idx_article_hot (status, del_flag, view_count);

-- yan_article_tag 已有 PRIMARY KEY (article_id, tag_id)，能够覆盖当前按 article_id
-- 查询、删除和替换标签关系的场景，因此不重复创建索引。
