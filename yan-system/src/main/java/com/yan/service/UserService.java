package com.yan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yan.domain.ResponseResult;
import com.yan.domain.entity.User;
import com.yan.domain.dto.AdminUserDto;

/**
 * 用户表(User)表服务接口
 *
 * @author makejava
 * @since 2025-11-28 18:45:11
 */
public interface UserService extends IService<User> {
    ResponseResult userInfo();

    ResponseResult updateUserInfo(User user);

    ResponseResult register(User user);

    ResponseResult listAdminUsers(Integer pageNum, Integer pageSize, String userName, String phonenumber, String status);

    ResponseResult getAdminUser(Long id);

    ResponseResult addAdminUser(AdminUserDto userDto);

    ResponseResult updateAdminUser(AdminUserDto userDto);

    ResponseResult deleteAdminUsers(java.util.List<Long> ids);

    ResponseResult updateAdminUserStatus(Long id, String status);

    ResponseResult resetAdminUserPassword(Long id, String password);
}
