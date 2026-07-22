package com.yan.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class MenuTreeVo {
    private Long id;
    private String label;
    private List<MenuTreeVo> children;
}
