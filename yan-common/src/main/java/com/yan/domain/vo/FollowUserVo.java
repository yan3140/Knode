package com.yan.domain.vo;

import lombok.Data;

import java.util.Date;

@Data
public class FollowUserVo {
    private Long userId;
    private String nickName;
    private String avatar;
    private Date followTime;
}
