package com.yan.domain.vo;

import com.yan.domain.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserInfoVo {
    private List<String> roles;

    private List<String> permissions;

    private UserInfoVo user;
}
