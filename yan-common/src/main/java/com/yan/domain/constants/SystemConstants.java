package com.yan.domain.constants;

public class SystemConstants {
    /**
     * 文章是草稿
     */
    public static final int ARTICLE_STATUS_DRAFT = 1;
    /**
     * 文章是正常分布状态
     */
    public static final int ARTICLE_STATUS_NORMAL = 0;
    /**
     * 分类是正常分布状态
     */
    public static final String CATEGORY_STATUS_NORMAL = "0";
    /**
     * 分类被禁止
     */
    public static final String CATEGORY_STATUS_DRAFT = "1";

    public static final String LINK_PENDING = "2";
    public static final String LINK_APPROVED = "0";
    public static final String LINK_REJECTED = "1";
    /**
     * 文章评论
     */
    public static final String ARTICLE_COMMENT = "0";
    /**
     * 友链评论
     */
    public static final String LINK_COMMENT = "1";

    public static final String MENU = "C";
    public static final String BUTTON = "F";
    public static final String TABLE = "M";
    public static final Integer TAG_NORMAL_STATUS = 0;
    public static final Integer TAG_DRAFT_STATUS = 1;
    public static final String NORMAL = "0";
}
