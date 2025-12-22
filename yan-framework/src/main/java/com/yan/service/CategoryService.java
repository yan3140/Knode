package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Category;

public interface CategoryService extends IService<Category> {
    ResponseResult getCategoryList();

    ResponseResult listAllCategory();

    ResponseResult listAll(Integer pageNum, Integer pageSize);
}
