package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.dto.ArticleDto;
import com.yan.domain.entity.Article;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.ArticleTag;
import com.yan.domain.entity.Category;
import com.yan.domain.vo.ArticleDetailVo;
import com.yan.domain.vo.ArticleListVo;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.ArticleTagMapper;
import com.yan.mapper.CategoryMapper;
import com.yan.service.ArticleService;
import com.yan.domain.vo.HotArticleVo;
import com.yan.service.ArticleTagService;
import com.yan.service.CategoryService;
import com.yan.service.TagService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.RedisCache;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.yan.domain.constants.SystemConstants.ARTICLE_STATUS_NORMAL;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private ArticleTagService articleTagService;

    @Override
    public ResponseResult hotArticleList() {
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
            article.setViewCount(getVC(article));
        }
        //bean拷贝
//        List<HotArticleVo> hotArticleVoList = new ArrayList<>();
//        for (Article article : articleList) {
//            HotArticleVo hotArticleVo = new HotArticleVo();
//            BeanUtils.copyProperties(article,hotArticleVo);
//            hotArticleVoList.add(hotArticleVo);
//        }
        List<HotArticleVo> hotArticleVoList = BeanCopyUtils.copyBeanList(articleList, HotArticleVo.class);
        return ResponseResult.okResult(hotArticleVoList);
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
            article.setViewCount(getVC(article));
            article.setCategoryName(categoryMap.get(article.getCategoryId()));
        }


        List<ArticleListVo> articleListVos = BeanCopyUtils.copyBeanList(page.getRecords(), ArticleListVo.class);

        PageVo pageVo = new PageVo(articleListVos, page.getTotal());
        return ResponseResult.okResult(pageVo);
    }

    @Override
    public ResponseResult getArticleDetail(Long id) {
        //根据id查询文章
        Article article = getById(id);
        //从redis中获取viewCount
//        Integer viewCount = redisCache.getCacheMapValue("article:viewCount", id.toString());
//        article.setViewCount(viewCount.longValue());
        article.setViewCount(getVC(article));
        //转换成VO
        ArticleDetailVo articleDetailVo = BeanCopyUtils.copyBean(article, ArticleDetailVo.class);
        //根据分类id查询分类名
        Long categoryId = articleDetailVo.getCategoryId();
        Category category = categoryMapper.selectById(categoryId);
        if (category != null) {
            articleDetailVo.setCategoryName(category.getName());
        }
        return ResponseResult.okResult(articleDetailVo);
    }

    @Override
    public ResponseResult updateViewCount(Long id) {
        //更新redis中对应的浏览量
        redisCache.incrementCacheMapValue("article:viewCount", id.toString(), 1);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult addArticle(ArticleDto articleDto) {
        Article article = BeanCopyUtils.copyBean(articleDto, Article.class);
        save(article);
        List<ArticleTag> articleTags = articleDto.getTags().stream()
                .map(tagId -> new ArticleTag(article.getId(), tagId))
                .toList();
        articleTagService.saveBatch(articleTags);
        return ResponseResult.okResult();
    }

    private Long getVC(Article article) {
        Long id = article.getId();
        Integer viewCount = redisCache.getCacheMapValue("article:viewCount", id.toString());
        return viewCount.longValue();
    }
}
