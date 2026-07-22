package com.yan.admin.controller;

import com.yan.domain.ResponseResult;
import com.yan.domain.dto.RoleDto;
import com.yan.domain.dto.StatusDto;
import com.yan.service.RoleService;
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

@RestController
@RequestMapping({"/system/role", "/api/admin/system/role"})
public class RoleController {
    @Autowired
    private RoleService roleService;

    @GetMapping("/list")
    public ResponseResult list(Integer pageNum, Integer pageSize, String roleName, String roleKey, String status) {
        return roleService.listRoles(pageNum, pageSize, roleName, roleKey, status);
    }

    @GetMapping("/listAllRole")
    public ResponseResult listAll() {
        return roleService.listAllRoles();
    }

    @GetMapping("/{id}")
    public ResponseResult get(@PathVariable Long id) {
        return roleService.getRole(id);
    }

    @PostMapping
    public ResponseResult add(@RequestBody RoleDto roleDto) {
        return roleService.addRole(roleDto);
    }

    @PutMapping
    public ResponseResult update(@RequestBody RoleDto roleDto) {
        return roleService.updateRole(roleDto);
    }

    @DeleteMapping("/{ids}")
    public ResponseResult delete(@PathVariable List<Long> ids) {
        return roleService.deleteRoles(ids);
    }

    @PutMapping("/changeStatus")
    public ResponseResult changeStatus(@RequestBody StatusDto statusDto) {
        return roleService.updateRoleStatus(statusDto.getId(), statusDto.getStatus());
    }
}
