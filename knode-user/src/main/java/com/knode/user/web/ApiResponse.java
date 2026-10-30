package com.knode.user.web;

import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse(int code,String msg,Object data) {
    public static ApiResponse ok() { return new ApiResponse(200,"操作成功",null); }
    public static ApiResponse ok(Object data) { return new ApiResponse(200,"操作成功",data); }
}
