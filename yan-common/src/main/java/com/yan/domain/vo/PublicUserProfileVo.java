package com.yan.domain.vo;

import lombok.Data;

import java.util.Date;

@Data
public class PublicUserProfileVo {
    private Long id;
    private String nickName;
    private String avatar;
    private Date createTime;
    private Long followingCount;
    private Long followerCount;
    private PageVo articles;
}
