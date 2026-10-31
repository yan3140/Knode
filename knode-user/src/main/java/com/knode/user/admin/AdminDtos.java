package com.knode.user.admin;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record UserInput(Long id,String userName,String nickName,String password,String status,String email,
                            String phonenumber,String sex,List<Long> roleIds) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record RoleInput(Long id,String roleName,String roleKey,Integer roleSort,String status,String remark,List<Long> menuIds) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record MenuInput(Long id,String menuName,Long parentId,Integer orderNum,String path,String component,
                            Integer isFrame,String menuType,String visible,String status,String perms,String icon,String remark) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record StatusInput(@JsonAlias({"userId","roleId"}) Long id,String status) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record PasswordInput(Long userId,String password) {}
    public record UserView(String id,String userName,String nickName,String status,String email,String phonenumber,
                           String sex,String createTime,List<String> roleIds) {}
    public record RoleView(String id,String roleName,String roleKey,int roleSort,String status,String delFlag,String createBy,String createTime,String updateBy,String updateTime,String remark,List<String> menuIds) {}
    public record MenuView(String id,String menuName,String parentId,int orderNum,String path,String component,Integer isFrame,
                           String menuType,String visible,String status,String perms,String icon,String createBy,String createTime,String updateBy,String updateTime,String remark,String delFlag) {}
    public record Tree(String id,String label,List<Tree> children) {}
    public record Page(List<?> rows,String total) {}
}
