package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.dto.ArticleDto;
import com.yan.constants.CacheConstants;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleTag;
import com.yan.domain.entity.Category;
import com.yan.domain.entity.Tag;
import com.yan.domain.entity.User;
import com.yan.domain.vo.ArticleDetailVo;
import com.yan.domain.vo.ArticleListVo;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.ArticleTagMapper;
import com.yan.mapper.CategoryMapper;
import com.yan.mapper.UserMapper;
import com.yan.service.ArticleService;
import com.yan.service.ArticleCounterService;
import com.yan.domain.vo.HotArticleVo;
import com.yan.service.ArticleTagService;
import com.yan.service.CategoryService;
import com.yan.service.TagService;
import com.yan.notification.NotificationFanoutOutboxService;
import com.yan.knowledge.source.KnowledgeArticleChangeService;
import com.yan.search.outbox.ArticleSearchOutboxService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.CacheClient;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.yan.domain.constants.SystemConstants.ARTICLE_STATUS_NORMAL;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleServiceImpl.class);

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private ArticleCounterService articleCounterService;
    @Autowired
    private ArticleTagService articleTagService;
    @Autowired
    private TagService tagService;
    @Autowired
    private ArticleSearchOutboxService articleSearchOutboxService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NotificationFanoutOutboxService notificationFanoutOutboxService;
    @Autowired
    private KnowledgeArticleChangeService knowledgeArticleChangeService;

    @Override
    public ResponseResult hotArticleList() {
        List<HotArticleVo> hotArticleVoList = cacheClient.queryWithMutex(
                CacheConstants.HOT_ARTICLE_LIST,
                CacheConstants.HOT_ARTICLE_TTL_MINUTES,
                CacheConstants.TTL_JITTER_MINUTES,
                this::loadHotArticleList
        );
        hotArticleVoList.forEach(article -> article.setViewCount(
                getVC(article.getId(), article.getViewCount())));
        return ResponseResult.okResult(hotArticleVoList);
    }

    private List<HotArticleVo> loadHotArticleList() {
        //查询热门文章 封装成ResponseResult返回
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<Article>();
        //必须是正式文章
        queryWrapper.eq(Article::getStatus, ARTICLE_STATUS_NORMAL);
        //按照浏览量进行排序
        queryWrapper.orderByDesc(Article::getViewCount);
        //最多只查询10条
        Page<Article> page = new Page<>(1, 10);
        page(page, queryWrapper);
        List<Article> articleList = page.getRecords();
        for (Article article : articleList) {
            article.setViewCount(getVC(article.getId(), article.getViewCount()));
        }
        //bean拷贝
//        List<HotArticleVo> hotArticleVoList = new ArrayList<>();
//        for (Article article : articleList) {
//            HotArticleVo hotArticleVo = new HotArticleVo();
//            BeanUtils.copyProperties(article,hotArticleVo);
//            hotArticleVoList.add(hotArticleVo);
//        }
        return BeanCopyUtils.copyBeanList(articleList, HotArticleVo.class);
    }

    @Override
    public ResponseResult articleList(Integer pageNum, Integer pageSize, Long categoryId) {
        //查询条件
        LambdaQueryWrapper<Article> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 如果 有categoryId 就要 查询时要和传入的相同
        lambdaQueryWrapper.eq(Objects.nonNull(categoryId) && categoryId > 0, Article::getCategoryId, categoryId);
        // 状态是正式发布的
        lambdaQueryWrapper.eq(Article::getStatus, ARTICLE_STATUS_NORMAL);
        // 对isTop进行降序
        lambdaQueryWrapper.orderByDesc(Article::getIsTop);
        //分页查询
        Page<Article> page = new Page<>(pageNum, pageSize);
        page(page, lambdaQueryWrapper);
        List<Article> articleList = page.getRecords();
        // 1. 获取所有分类ID（Java 8 Stream 写法）
        Set<Long> categoryIds = articleList.stream()
                .map(Article::getCategoryId)
                .collect(Collectors.toSet());

        // 2. 批量查询分类（一次 SQL 查完，代替循环里的 N 次 SQL）
        // 这里建议注入 CategoryMapper 来调用 selectBatchIds
        List<Category> categories = categoryMapper.selectBatchIds(categoryIds);

        // 3. 转成 Map 为了方便存取: key是id, value是name
        Map<Long, String> categoryMap = categories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        // 4. 填入名称
        for (Article article : articleList) {
            article.setViewCount(getVC(article.getId(), article.getViewCount()));
            article.setCategoryName(categoryMap.get(article.getCategoryId()));
        }


        List<ArticleListVo> articleListVos = toArticleListVos(page.getRecords(), categoryMap);

        PageVo pageVo = new PageVo(articleListVos, page.getTotal());
        return ResponseResult.okResult(pageVo);
    }

    @Override
    public ResponseResult getArticleDetail(Long id) {
        ArticleDetailVo articleDetailVo = cacheClient.queryWithMutex(
                CacheConstants.ARTICLE_DETAIL_PREFIX + id,
                CacheConstants.ARTICLE_DETAIL_TTL_MINUTES,
                CacheConstants.TTL_JITTER_MINUTES,
                () -> loadArticleDetail(id)
        );
        if (articleDetailVo == null) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        articleDetailVo.setViewCount(getVC(articleDetailVo.getId(), articleDetailVo.getViewCount()));
        return ResponseResult.okResult(articleDetailVo);
    }

    private ArticleDetailVo loadArticleDetail(Long id) {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Article::getId, id);
        queryWrapper.eq(Article::getStatus, ARTICLE_STATUS_NORMAL);
        Article article = getOne(queryWrapper);
        if (article == null) {
            return null;
        }
        article.setViewCount(getVC(article.getId(), article.getViewCount()));
        //转换成VO
        ArticleDetailVo articleDetailVo = BeanCopyUtils.copyBean(article, ArticleDetailVo.class);
        //根据分类id查询分类名
        Long categoryId = articleDetailVo.getCategoryId();
        Category category = categoryMapper.selectById(categoryId);
        if (category != null) {
            articleDetailVo.setCategoryName(category.getName());
        }
        User author = userMapper.selectById(article.getCreateBy());
        articleDetailVo.setAuthorId(article.getCreateBy());
        if (author != null) {
            articleDetailVo.setAuthorName(author.getNickName());
            articleDetailVo.setAuthorAvatar(author.getAvatar());
        }
        return articleDetailVo;
    }

    private List<ArticleListVo> toArticleListVos(List<Article> articles, Map<Long, String> categoryMap) {
        if (articles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> authorIds = articles.stream().map(Article::getCreateBy)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> authors = authorIds.isEmpty() ? Collections.emptyMap() : userMapper.selectBatchIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        List<ArticleListVo> result = BeanCopyUtils.copyBeanList(articles, ArticleListVo.class);
        for (int index = 0; index < result.size(); index++) {
            ArticleListVo articleVo = result.get(index);
            Article source = articles.get(index);
            articleVo.setCategoryName(categoryMap.get(source.getCategoryId()));
            articleVo.setAuthorId(source.getCreateBy());
            User author = authors.get(source.getCreateBy());
            if (author != null) {
                articleVo.setAuthorName(author.getNickName());
                articleVo.setAuthorAvatar(author.getAvatar());
            }
        }
        return result;
    }

    @Override
    public ResponseResult updateViewCount(Long id, String visitorFingerprint) {
        articleCounterService.increaseViewCount(id, visitorFingerprint);
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addArticle(ArticleDto articleDto) {
        validateArticle(articleDto, false);
        Article article = BeanCopyUtils.copyBean(articleDto, Article.class);
        if (!save(article)) {
            throw new SystemException(AppHttpCodeEnum.SYSTEM_ERROR);
        }
        saveArticleTags(article.getId(), articleDto.getTags());
        evictArticleCaches(List.of(article.getId()));
        synchronizeCounterAfterCommit(article.getId());
        articleSearchOutboxService.enqueue(article.getId());
        knowledgeArticleChangeService.synchronize(article);
        if (String.valueOf(ARTICLE_STATUS_NORMAL).equals(article.getStatus())) {
            notificationFanoutOutboxService.enqueue(article.getId(), article.getCreateBy());
        }
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult listArticle(Integer pageNum, Integer pageSize, String title, String summary,
                                      Long categoryId, String status) {
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.hasText(title), Article::getTitle, title);
        queryWrapper.like(StringUtils.hasText(summary), Article::getSummary, summary);
        queryWrapper.eq(categoryId != null, Article::getCategoryId, categoryId);
        queryWrapper.eq(StringUtils.hasText(status), Article::getStatus, status);
        queryWrapper.orderByDesc(Article::getCreateTime);
        Page<Article> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        page(page, queryWrapper);
        List<Article> articles = page.getRecords();
        Set<Long> categoryIds = articles.stream()
                .map(Article::getCategoryId)
                .collect(Collectors.toSet());
        if (!categoryIds.isEmpty()) {
            Map<Long, String> categoryNames = categoryMapper.selectBatchIds(categoryIds).stream()
                    .collect(Collectors.toMap(Category::getId, Category::getName));
            articles.forEach(article -> article.setCategoryName(categoryNames.get(article.getCategoryId())));
        }
        return ResponseResult.okResult(new PageVo(articles, page.getTotal()));
    }

    @Override
    public ResponseResult getArticle(Long id) {
        Article article = getById(id);
        if (article == null) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        ArticleDto articleDto = BeanCopyUtils.copyBean(article, ArticleDto.class);
        List<Long> tags = articleTagService.list(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, id))
                .stream()
                .map(ArticleTag::getTagId)
                .toList();
        articleDto.setTags(tags);
        return ResponseResult.okResult(articleDto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateArticle(ArticleDto articleDto) {
        validateArticle(articleDto, true);
        Article article = BeanCopyUtils.copyBean(articleDto, Article.class);
        if (!updateById(article)) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        articleTagService.remove(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleDto.getId()));
        saveArticleTags(articleDto.getId(), articleDto.getTags());
        evictArticleCaches(List.of(articleDto.getId()));
        synchronizeCounterAfterCommit(articleDto.getId());
        articleSearchOutboxService.enqueue(articleDto.getId());
        knowledgeArticleChangeService.synchronize(article);
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteArticle(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
        }
        articleTagService.remove(new LambdaQueryWrapper<ArticleTag>()
                .in(ArticleTag::getArticleId, ids));
        removeByIds(ids);
        evictArticleCaches(ids);
        removeCountersAfterCommit(ids);
        ids.forEach(articleSearchOutboxService::enqueue);
        ids.forEach(knowledgeArticleChangeService::enqueueDeletion);
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateArticleStatus(Long id, String status) {
        if (id == null || !isBinary(status)) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
        }
        boolean updated = update(new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getStatus, status));
        if (!updated) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_NOT_FOUND);
        }
        evictArticleCaches(List.of(id));
        synchronizeCounterAfterCommit(id);
        articleSearchOutboxService.enqueue(id);
        knowledgeArticleChangeService.synchronize(getById(id));
        return ResponseResult.okResult();
    }

    private void validateArticle(ArticleDto articleDto, boolean updating) {
        if (articleDto == null || !StringUtils.hasText(articleDto.getTitle())
                || !StringUtils.hasText(articleDto.getContent()) || articleDto.getCategoryId() == null
                || articleDto.getTags() == null || !isBinary(articleDto.getStatus())
                || !isBinary(articleDto.getIsTop()) || !isBinary(articleDto.getIsComment())
                || (updating && articleDto.getId() == null)) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
        }
        Category category = categoryMapper.selectById(articleDto.getCategoryId());
        if (category == null || !SystemConstants.CATEGORY_STATUS_NORMAL.equals(category.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
        }
        List<Long> tagIds = articleDto.getTags();
        if (new HashSet<>(tagIds).size() != tagIds.size()) {
            throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
        }
        if (!tagIds.isEmpty()) {
            List<Tag> tags = tagService.listByIds(tagIds);
            if (tags.size() != tagIds.size()) {
                throw new SystemException(AppHttpCodeEnum.ARTICLE_PARAM_ERROR);
            }
        }
    }

    private void saveArticleTags(Long articleId, List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return;
        }
        List<ArticleTag> articleTags = tagIds.stream()
                .map(tagId -> new ArticleTag(articleId, tagId))
                .toList();
        if (!articleTagService.saveBatch(articleTags)) {
            throw new SystemException(AppHttpCodeEnum.SYSTEM_ERROR);
        }
    }

    private boolean isBinary(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    private void evictArticleCaches(Collection<Long> articleIds) {
        List<String> keys = new ArrayList<>();
        keys.add(CacheConstants.HOT_ARTICLE_LIST);
        keys.add(CacheConstants.CATEGORY_LIST);
        articleIds.stream()
                .filter(Objects::nonNull)
                .map(id -> CacheConstants.ARTICLE_DETAIL_PREFIX + id)
                .forEach(keys::add);
        articleIds.stream()
                .filter(Objects::nonNull)
                .map(id -> CacheConstants.ARTICLE_COMMENT_FIRST_PAGE_PREFIX + id)
                .forEach(keys::add);
        cacheClient.deleteAfterCommit(keys.toArray(String[]::new));
        cacheClient.deletePatternAfterCommit(CacheConstants.SEARCH_RESULT_PATTERN);
    }

    private Long getVC(Long id, Long databaseValue) {
        return articleCounterService.getViewCount(id, databaseValue);
    }

    private void synchronizeCounterAfterCommit(Long articleId) {
        runAfterCommit(() -> articleCounterService.synchronizeArticle(articleId));
    }

    private void removeCountersAfterCommit(Collection<Long> articleIds) {
        List<Long> ids = List.copyOf(articleIds);
        runAfterCommit(() -> articleCounterService.removeArticles(ids));
    }

    private void runAfterCommit(Runnable action) {
        Runnable safeAction = () -> {
            try {
                action.run();
            } catch (RuntimeException redisFailure) {
                LOGGER.warn("文章变更已提交，但浏览量缓存同步失败，将由启动初始化补偿", redisFailure);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeAction.run();
                }
            });
        } else {
            safeAction.run();
        }
    }
}
