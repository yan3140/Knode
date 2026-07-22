package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Article;
import com.yan.domain.entity.Category;
import com.yan.domain.entity.User;
import com.yan.domain.entity.UserFollow;
import com.yan.domain.vo.ArticleListVo;
import com.yan.domain.vo.PageVo;
import com.yan.domain.vo.PublicUserProfileVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.ArticleMapper;
import com.yan.mapper.CategoryMapper;
import com.yan.mapper.UserFollowMapper;
import com.yan.mapper.UserMapper;
import com.yan.service.UserProfileService;
import com.yan.utils.BeanCopyUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserProfileServiceImpl implements UserProfileService {
    private final UserMapper userMapper;
    private final ArticleMapper articleMapper;
    private final CategoryMapper categoryMapper;
    private final UserFollowMapper userFollowMapper;

    public UserProfileServiceImpl(UserMapper userMapper, ArticleMapper articleMapper,
                                  CategoryMapper categoryMapper, UserFollowMapper userFollowMapper) {
        this.userMapper = userMapper;
        this.articleMapper = articleMapper;
        this.categoryMapper = categoryMapper;
        this.userFollowMapper = userFollowMapper;
    }

    @Override
    public ResponseResult profile(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        User user = userMapper.selectById(userId);
        if (user == null || !"0".equals(user.getStatus())) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        Page<Article> page = articleMapper.selectPage(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)),
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getCreateBy, userId)
                        .eq(Article::getStatus, "0")
                        .orderByDesc(Article::getCreateTime));
        PublicUserProfileVo profile = new PublicUserProfileVo();
        profile.setId(user.getId());
        profile.setNickName(user.getNickName());
        profile.setAvatar(user.getAvatar());
        profile.setCreateTime(user.getCreateTime());
        profile.setFollowingCount(userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, userId)));
        profile.setFollowerCount(userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFolloweeId, userId)));
        profile.setArticles(new PageVo(toArticleList(page.getRecords()), page.getTotal()));
        return ResponseResult.okResult(profile);
    }

    private List<ArticleListVo> toArticleList(List<Article> articles) {
        if (articles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> categoryIds = articles.stream().map(Article::getCategoryId).collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        List<ArticleListVo> result = BeanCopyUtils.copyBeanList(articles, ArticleListVo.class);
        result.forEach(article -> article.setCategoryName(categoryNames.get(article.getCategoryId())));
        return result;
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 20);
    }
}
