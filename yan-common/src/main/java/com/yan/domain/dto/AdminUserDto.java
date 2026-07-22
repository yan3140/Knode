package com.yan.domain.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class AdminUserDto {
    private Long id;
    private String userName;
    private String nickName;
    private String password;
    private String status;
    private String email;
    private String phonenumber;
    private String sex;
    private Date createTime;
    private List<Long> roleIds;
}
