package com.yan.constants;

public final class CacheConstants {
    public static final String ARTICLE_DETAIL_PREFIX = "blog:article:detail:";
    public static final String HOT_ARTICLE_LIST = "blog:article:hot:list";
    public static final String CATEGORY_LIST = "blog:category:list";
    public static final String ALL_CATEGORY_LIST = "blog:category:all";
    public static final String ALL_TAG_LIST = "blog:tag:all";
    public static final String ARTICLE_VIEW_COUNT = "blog:article:view-count";
    public static final String ARTICLE_VIEW_COUNT_DIRTY = "blog:article:view-count:dirty";
    public static final String ARTICLE_VIEW_COUNT_PUBLISHED = "blog:article:view-count:published";
    public static final String ARTICLE_VIEWED_PREFIX = "blog:article:viewed:";
    public static final String LEGACY_ARTICLE_VIEW_COUNT = "article:viewCount";

    public static final int ARTICLE_DETAIL_TTL_MINUTES = 30;
    public static final int HOT_ARTICLE_TTL_MINUTES = 10;
    public static final int DICTIONARY_TTL_MINUTES = 60;
    public static final int TTL_JITTER_MINUTES = 10;
    public static final int ARTICLE_VIEW_DEDUP_MINUTES = 30;
    public static final int ARTICLE_VIEW_FLUSH_BATCH_SIZE = 100;

    private CacheConstants() {
    }
}
