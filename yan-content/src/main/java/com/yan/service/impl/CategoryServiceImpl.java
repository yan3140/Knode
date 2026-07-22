package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.constants.CacheConstants;
import com.yan.domain.constants.SystemConstants;
import com.yan.domain.entity.Article;
import com.yan.domain.entity.Category;
import com.yan.domain.vo.CategoryVo;
import com.yan.domain.vo.PageVo;
import com.yan.mapper.CategoryMapper;
import com.yan.service.ArticleService;
import com.yan.service.CategoryService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service("CategoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {
    @Autowired
    private ArticleService articleService;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public ResponseResult getCategoryList() {
        List<CategoryVo> categoryVos = cacheClient.queryWithMutex(
                CacheConstants.CATEGORY_LIST,
                CacheConstants.DICTIONARY_TTL_MINUTES,
                CacheConstants.TTL_JITTER_MINUTES,
                this::loadCategoryList
        );
        return ResponseResult.okResult(categoryVos);
    }

    private List<CategoryVo> loadCategoryList() {
        LambdaQueryWrapper<Article> articleWrapper = new LambdaQueryWrapper<Article>();
        articleWrapper.eq(Article::getStatus, SystemConstants.ARTICLE_STATUS_NORMAL);
        List<Article> articleList = articleService.list(articleWrapper);
        Set<Long> categoryIds = articleList.stream()
                .map(Article::getCategoryId)
                .collect(Collectors.toSet());
        List<Category> categoryList = listByIds(categoryIds);
        categoryList = categoryList.stream()
                .filter(category -> SystemConstants.CATEGORY_STATUS_NORMAL.equals(category.getStatus()))
                .toList();
        return BeanCopyUtils.copyBeanList(categoryList, CategoryVo.class);

    }

    @Override
    public ResponseResult listAllCategory() {
        List<CategoryVo> categoryVos = cacheClient.queryWithMutex(
                CacheConstants.ALL_CATEGORY_LIST,
                CacheConstants.DICTIONARY_TTL_MINUTES,
                CacheConstants.TTL_JITTER_MINUTES,
                this::loadAllCategories
        );
        return ResponseResult.okResult(categoryVos);
    }

    private List<CategoryVo> loadAllCategories() {
        LambdaQueryWrapper<Category> categoryWrapper = new LambdaQueryWrapper<>();
        categoryWrapper.eq(Category::getStatus,SystemConstants.NORMAL);
        List<Category> categories = list(categoryWrapper);
        return BeanCopyUtils.copyBeanList(categories, CategoryVo.class);
    }

    @Override
    public ResponseResult listAll(Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Category> categoryWrapper = new LambdaQueryWrapper<>();
        Page<Category> page = new Page(pageNum, pageSize);
        page(page, categoryWrapper);
        List<CategoryVo> categoryVos = BeanCopyUtils.copyBeanList(page.getRecords(), CategoryVo.class);
        return ResponseResult.okResult(new PageVo(categoryVos, page.getTotal()));
    }
}
