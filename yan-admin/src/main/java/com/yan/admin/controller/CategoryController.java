package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.entity.Category;
import com.yan.service.CategoryService;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminCategoryController")
@RequestMapping({"/content/category", "/api/admin/content/category"})
public class CategoryController {

    @Autowired
    CategoryService categoryService;
    @GetMapping("/listAllCategory")
    public ResponseResult listAllCategory(){
        return categoryService.listAllCategory();
    }

    @GetMapping("/list")
    public ResponseResult listAll(Integer pageNum, Integer pageSize){
        return categoryService.listAll(pageNum,pageSize);
    }
}
