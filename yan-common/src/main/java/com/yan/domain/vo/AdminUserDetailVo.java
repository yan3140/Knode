package com.yan.domain.vo;

import com.yan.domain.dto.AdminUserDto;
import com.yan.domain.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AdminUserDetailVo {
    private AdminUserDto user;
    private List<Role> roles;
    private List<Long> roleIds;
}
