package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.dto.AdminUserDto;
import com.yan.domain.dto.PasswordDto;
import com.yan.domain.dto.StatusDto;
import com.yan.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("adminUserController")
@RequestMapping({"/system/user", "/api/admin/system/user"})
public class UserController {
    @Autowired
    private UserService userService;

    @GetMapping("/list")
    public ResponseResult list(Integer pageNum, Integer pageSize, String userName, String phonenumber, String status) {
        return userService.listAdminUsers(pageNum, pageSize, userName, phonenumber, status);
    }

    @GetMapping("/{id}")
    public ResponseResult get(@PathVariable Long id) {
        return userService.getAdminUser(id);
    }

    @PostMapping
    public ResponseResult add(@RequestBody AdminUserDto userDto) {
        return userService.addAdminUser(userDto);
    }

    @PutMapping
    public ResponseResult update(@RequestBody AdminUserDto userDto) {
        return userService.updateAdminUser(userDto);
    }

    @DeleteMapping("/{ids}")
    public ResponseResult delete(@PathVariable List<Long> ids) {
        return userService.deleteAdminUsers(ids);
    }

    @PutMapping("/changeStatus")
    public ResponseResult changeStatus(@RequestBody StatusDto statusDto) {
        return userService.updateAdminUserStatus(statusDto.getId(), statusDto.getStatus());
    }

    @PutMapping("/resetPwd")
    public ResponseResult resetPassword(@RequestBody PasswordDto passwordDto) {
        return userService.resetAdminUserPassword(passwordDto.getUserId(), passwordDto.getPassword());
    }
}
