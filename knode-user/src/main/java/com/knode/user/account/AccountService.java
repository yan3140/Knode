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
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final com.knode.user.auth.SessionStore.Writer sessionWriter;
    public AccountService(UserRepository users,BCryptPasswordEncoder encoder,UserTokens tokens,
                          org.springframework.jdbc.core.JdbcTemplate db,com.knode.user.auth.SessionStore.Writer sessionWriter) {
        this.users=users;this.encoder=encoder;this.tokens=tokens;this.db=db;this.sessionWriter=sessionWriter;
    }
    public record LoginResult(String token,UserRepository.UserInfo userInfo) {}
    @org.springframework.transaction.annotation.Transactional
    public LoginResult login(String username,String password,String scope,Long resolvedId) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password) || resolvedId==null) throw new BusinessFailure(505,"用户名或密码错误");
        var user=users.byUsernameForUpdate(username).orElseThrow(() -> new BusinessFailure(505,"用户名或密码错误"));
        if (user.id()!=resolvedId || !user.active() || !encoder.matches(password,user.password())) throw new BusinessFailure(505,"用户名或密码错误");
        if ("admin".equals(scope) && !"1".equals(user.type())) throw new AuthenticationFailure(403);
        var issued=tokens.issueDetailed(Long.toString(user.id()),scope);
        try {
            db.update("INSERT INTO auth_login_session(sid,user_id,scope,expires_at,active) VALUES (?,?,?,?,1)",
                    issued.principal().sid(),user.id(),scope,java.sql.Timestamp.from(java.time.Instant.ofEpochSecond(issued.expiresAt())));
        } catch (RuntimeException e) {
            try { sessionWriter.revoke(issued.principal().sid()); }
            catch (RuntimeException ignored) { /* Token has not left the server. */ }
            throw e;
        }
        return new LoginResult(issued.token(),user.info());
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
    public boolean sessionActive(String sid,long userId,String scope) {
        return db.queryForObject("SELECT COUNT(*) FROM auth_login_session WHERE sid=? AND user_id=? AND scope=? AND active=1 AND expires_at>CURRENT_TIMESTAMP",Integer.class,sid,userId,scope)>0;
    }
    public void update(long id,String nickname,String avatar,String sex) {
        if (!StringUtils.hasText(nickname) || nickname.length()>64 || (avatar!=null && avatar.length()>512)
                || sex==null || !java.util.Set.of("0","1","2").contains(sex)) throw new BusinessFailure(515,"参数不合法");
        try { if (users.updateProfile(id,nickname,avatar,sex)!=1) throw new AuthenticationFailure(401); }
        catch (DataIntegrityViolationException e) { if (users.exists("nick_name",nickname)) throw new BusinessFailure(512,"昵称已存在"); throw e; }
    }
}
