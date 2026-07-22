package com.yan.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RoleMenuTreeVo {
    private List<MenuTreeVo> menus;
    private List<Long> checkedKeys;
}
