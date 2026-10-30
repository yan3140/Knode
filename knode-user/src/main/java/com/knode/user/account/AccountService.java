package com.knode.user.account;

import com.knode.user.auth.AuthenticationFailure;
import com.knode.user.auth.UserTokens;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccountService {
    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    private final UserTokens tokens;
    public AccountService(UserRepository users,BCryptPasswordEncoder encoder,UserTokens tokens) {
        this.users=users;this.encoder=encoder;this.tokens=tokens;
    }
    public record LoginResult(String token,UserRepository.UserInfo userInfo) {}
    public LoginResult login(String username,String password,String scope) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) throw new BusinessFailure(505,"用户名或密码错误");
        var user=users.byUsername(username).orElseThrow(() -> new BusinessFailure(505,"用户名或密码错误"));
        if (!user.active() || !encoder.matches(password,user.password())) throw new BusinessFailure(505,"用户名或密码错误");
        if ("admin".equals(scope) && !"1".equals(user.type())) throw new AuthenticationFailure(403);
        return new LoginResult(tokens.issue(Long.toString(user.id()),scope),user.info());
    }
    public void register(String username,String nickname,String password,String email) {
        if (!StringUtils.hasText(username)) throw new BusinessFailure(508,"用户名不能为空");
        if (!StringUtils.hasText(nickname)) throw new BusinessFailure(509,"昵称不能为空");
        if (!StringUtils.hasText(password)) throw new BusinessFailure(510,"密码不能为空");
        if (!StringUtils.hasText(email)) throw new BusinessFailure(511,"邮箱不能为空");
        if (username.length()>64 || nickname.length()>64 || email.length()>255 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new BusinessFailure(515,"参数不合法");
        checkDuplicate(username,nickname,email);
        try { users.insert(username,nickname,encoder.encode(password),email); }
        catch (DataIntegrityViolationException e) { checkDuplicate(username,nickname,email); throw e; }
    }
    private void checkDuplicate(String username,String nickname,String email) {
        if (users.exists("user_name",username)) throw new BusinessFailure(501,"用户名已存在");
        if (users.exists("nick_name",nickname)) throw new BusinessFailure(512,"昵称已存在");
        if (users.exists("email",email)) throw new BusinessFailure(503,"邮箱已存在");
    }
    public UserRepository.Account active(long id,String scope) {
        var user=users.byId(id).filter(UserRepository.Account::active).orElseThrow(() -> new AuthenticationFailure(401));
        if ("admin".equals(scope) && !"1".equals(user.type())) throw new AuthenticationFailure(403);
        return user;
    }
    public void update(long id,String nickname,String avatar,String sex) {
        if (!StringUtils.hasText(nickname) || nickname.length()>64 || (avatar!=null && avatar.length()>512)
                || sex==null || !java.util.Set.of("0","1","2").contains(sex)) throw new BusinessFailure(515,"参数不合法");
        try { if (users.updateProfile(id,nickname,avatar,sex)!=1) throw new AuthenticationFailure(401); }
        catch (DataIntegrityViolationException e) { if (users.exists("nick_name",nickname)) throw new BusinessFailure(512,"昵称已存在"); throw e; }
    }
}
