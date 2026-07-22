package com.yan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yan.domain.ResponseResult;
import com.yan.domain.dto.AdminUserDto;
import com.yan.domain.entity.Role;
import com.yan.domain.entity.User;
import com.yan.domain.entity.UserRole;
import com.yan.domain.vo.AdminUserDetailVo;
import com.yan.domain.vo.PageVo;
import com.yan.domain.vo.UserInfoVo;
import com.yan.enums.AppHttpCodeEnum;
import com.yan.exception.SystemException;
import com.yan.mapper.RoleMapper;
import com.yan.mapper.UserMapper;
import com.yan.mapper.UserRoleMapper;
import com.yan.service.UserService;
import com.yan.utils.BeanCopyUtils;
import com.yan.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service("userService")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private static final Long ADMIN_USER_ID = 1L;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private RoleMapper roleMapper;

    @Override
    public ResponseResult userInfo() {
        User user = getById(SecurityUtils.getUserId());
        return ResponseResult.okResult(BeanCopyUtils.copyBean(user, UserInfoVo.class));
    }

    @Override
    public ResponseResult updateUserInfo(User user) {
        Long userId = SecurityUtils.getUserId();
        User current = getById(userId);
        if (current == null) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        User update = new User();
        update.setId(userId);
        update.setNickName(user.getNickName());
        update.setAvatar(user.getAvatar());
        update.setSex(user.getSex());
        updateById(update);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult register(User user) {
        if (!StringUtils.hasText(user.getUserName())) {
            throw new SystemException(AppHttpCodeEnum.USERNAME_NOT_NULL);
        }
        if (!StringUtils.hasText(user.getNickName())) {
            throw new SystemException(AppHttpCodeEnum.NICKNAME_NOT_NULL);
        }
        if (!StringUtils.hasText(user.getPassword())) {
            throw new SystemException(AppHttpCodeEnum.PASSWORD_NOT_NULL);
        }
        if (!StringUtils.hasText(user.getEmail())) {
            throw new SystemException(AppHttpCodeEnum.EMAIL_NOT_NULL);
        }
        if (userNameIsExist(user.getUserName())) {
            throw new SystemException(AppHttpCodeEnum.USERNAME_EXIST);
        }
        if (nickNameIsExist(user.getNickName())) {
            throw new SystemException(AppHttpCodeEnum.NIKENAME_EXIST);
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        save(user);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult listAdminUsers(Integer pageNum, Integer pageSize, String userName, String phonenumber, String status) {
        Page<User> page = page(new Page<>(normalizePage(pageNum), normalizeSize(pageSize)), new LambdaQueryWrapper<User>()
                .like(StringUtils.hasText(userName), User::getUserName, userName)
                .like(StringUtils.hasText(phonenumber), User::getPhonenumber, phonenumber)
                .eq(StringUtils.hasText(status), User::getStatus, status)
                .orderByDesc(User::getCreateTime));
        List<AdminUserDto> rows = BeanCopyUtils.copyBeanList(page.getRecords(), AdminUserDto.class);
        rows.forEach(user -> user.setPassword(null));
        return ResponseResult.okResult(new PageVo(rows, page.getTotal()));
    }

    @Override
    public ResponseResult getAdminUser(Long id) {
        User entity = getById(id);
        if (entity == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        AdminUserDto user = BeanCopyUtils.copyBean(entity, AdminUserDto.class);
        user.setPassword(null);
        List<Long> roleIds = getRoleIds(id);
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .eq(Role::getStatus, "0").orderByAsc(Role::getRoleSort));
        return ResponseResult.okResult(new AdminUserDetailVo(user, roles, roleIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addAdminUser(AdminUserDto userDto) {
        validateNewUser(userDto);
        if (userNameIsExist(userDto.getUserName())) {
            throw new SystemException(AppHttpCodeEnum.USERNAME_EXIST);
        }
        if (nickNameIsExist(userDto.getNickName())) {
            throw new SystemException(AppHttpCodeEnum.NIKENAME_EXIST);
        }
        User user = BeanCopyUtils.copyBean(userDto, User.class);
        user.setPassword(passwordEncoder.encode(userDto.getPassword()));
        user.setType("1");
        user.setDelFlag(0);
        save(user);
        replaceUserRoles(user.getId(), userDto.getRoleIds());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateAdminUser(AdminUserDto userDto) {
        if (userDto == null || userDto.getId() == null || !StringUtils.hasText(userDto.getNickName())
                || !StringUtils.hasText(userDto.getStatus()) || !StringUtils.hasText(userDto.getSex())) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        User current = getById(userDto.getId());
        if (current == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        if (nickNameIsExistForOtherUser(userDto.getNickName(), userDto.getId())) {
            throw new SystemException(AppHttpCodeEnum.NIKENAME_EXIST);
        }
        User user = BeanCopyUtils.copyBean(userDto, User.class);
        user.setUserName(current.getUserName());
        user.setPassword(null);
        user.setType(current.getType());
        updateById(user);
        replaceUserRoles(userDto.getId(), userDto.getRoleIds());
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteAdminUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.contains(ADMIN_USER_ID)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.ADMIN_OPERATION_FORBIDDEN);
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().in(UserRole::getUserId, ids));
        removeByIds(ids);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult updateAdminUserStatus(Long id, String status) {
        if (ADMIN_USER_ID.equals(id) || !StringUtils.hasText(status) || getById(id) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        updateById(user);
        return ResponseResult.okResult();
    }

    @Override
    public ResponseResult resetAdminUserPassword(Long id, String password) {
        if (id == null || !StringUtils.hasText(password) || getById(id) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_ERROR);
        }
        User user = new User();
        user.setId(id);
        user.setPassword(passwordEncoder.encode(password));
        updateById(user);
        return ResponseResult.okResult();
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = roleIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!distinctIds.isEmpty() && roleMapper.selectCount(new LambdaQueryWrapper<Role>().in(Role::getId, distinctIds)) != distinctIds.size()) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
        distinctIds.forEach(roleId -> {
            UserRole relation = new UserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            userRoleMapper.insert(relation);
        });
    }

    private List<Long> getRoleIds(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
                .stream().map(UserRole::getRoleId).filter(Objects::nonNull).toList();
    }

    private void validateNewUser(AdminUserDto userDto) {
        if (userDto == null || !StringUtils.hasText(userDto.getUserName()) || !StringUtils.hasText(userDto.getNickName())
                || !StringUtils.hasText(userDto.getPassword()) || !StringUtils.hasText(userDto.getStatus())
                || !StringUtils.hasText(userDto.getSex())) {
            throw new SystemException(AppHttpCodeEnum.PARAM_ERROR);
        }
    }

    private boolean userNameIsExist(String userName) {
        return count(new LambdaQueryWrapper<User>().eq(User::getUserName, userName)) > 0;
    }

    private boolean nickNameIsExist(String nickName) {
        return count(new LambdaQueryWrapper<User>().eq(User::getNickName, nickName)) > 0;
    }

    private boolean nickNameIsExistForOtherUser(String nickName, Long userId) {
        return count(new LambdaQueryWrapper<User>().eq(User::getNickName, nickName).ne(User::getId, userId)) > 0;
    }

    private long normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 100);
    }
}
