package com.knode.user.admin;

import com.knode.user.auth.RevocationCoordinator;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import static com.knode.user.admin.AdminDtos.*;

@Service
public class AdminUserService {
    private final AdminSupport a;
    private final BCryptPasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final RevocationCoordinator revocations;
    public AdminUserService(AdminSupport a,BCryptPasswordEncoder passwords,TransactionTemplate tx,RevocationCoordinator revocations) {
        this.a=a;this.passwords=passwords;this.tx=tx;this.revocations=revocations;
    }
    private UserView view(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new UserView(Long.toString(r.getLong("id")),r.getString("user_name"),r.getString("nick_name"),
                r.getString("status"),r.getString("email"),r.getString("phonenumber"),r.getString("sex"),AdminSupport.date(r.getTimestamp("create_time")),null);
    }
    private List<Long> roles(long id) {
        return a.db.query("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id",(r,n)->r.getLong(1),id);
    }
    public Page list(long actor,Integer page,Integer size,String userName,String phone,String status) {
        a.require(actor,"system:user:list");
        StringBuilder where=new StringBuilder(" WHERE del_flag=0");List<Object> args=new ArrayList<>();
        if (StringUtils.hasText(userName)) { where.append(" AND user_name LIKE ?");args.add("%"+userName+"%"); }
        if (StringUtils.hasText(phone)) { where.append(" AND phonenumber LIKE ?");args.add("%"+phone+"%"); }
        if (StringUtils.hasText(status)) { AdminSupport.status(status);where.append(" AND status=?");args.add(status); }
        Long total=a.db.queryForObject("SELECT COUNT(*) FROM sys_user"+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(AdminSupport.size(size));pageArgs.add(AdminSupport.offset(page,size));
        var rows=a.db.query("SELECT * FROM sys_user"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",this::view,pageArgs.toArray());
        return new Page(rows,Long.toString(total));
    }
    public Map<String,Object> detail(long actor,long id) {
        a.require(actor,"system:user:query");
        var rows=a.db.query("SELECT * FROM sys_user WHERE id=? AND del_flag=0",this::view,id);
        if (rows.isEmpty()) throw AdminSupport.invalid();
        var available=a.db.query("SELECT * FROM sys_role WHERE status='0' AND del_flag='0' ORDER BY role_sort,id",
                (r,n)->new RoleView(Long.toString(r.getLong("id")),r.getString("role_name"),r.getString("role_key"),r.getInt("role_sort"),r.getString("status"),r.getString("del_flag"),
                        AdminSupport.id(r,"create_by"),AdminSupport.date(r.getTimestamp("create_time")),AdminSupport.id(r,"update_by"),AdminSupport.date(r.getTimestamp("update_time")),r.getString("remark"),null));
        return Map.of("user",rows.get(0),"roles",available,"roleIds",roles(id).stream().map(String::valueOf).toList());
    }
    private void validate(UserInput u,boolean adding) {
        if (u==null || !StringUtils.hasText(u.nickName()) || u.nickName().length()>64 || !StringUtils.hasText(u.email())
                || u.email().length()>255 || u.sex()==null || !Set.of("0","1","2").contains(u.sex())
                || (u.phonenumber()!=null && u.phonenumber().length()>32)) throw AdminSupport.invalid();
        AdminSupport.status(u.status());
        if (adding && (!StringUtils.hasText(u.userName()) || u.userName().length()>64 || !StringUtils.hasText(u.password())
                || u.password().getBytes(StandardCharsets.UTF_8).length>72)) throw AdminSupport.invalid();
    }
    private void replaceRoles(long id,List<Long> roles) {
        a.db.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        if (roles!=null) for (Long role:new LinkedHashSet<>(roles)) a.db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
    }
    @Transactional
    public void add(long actor,UserInput u) {
        a.lock();a.require(actor,"system:user:add");validate(u,true);a.allowRoles(actor,u.roleIds());
        int before=a.manageable(); var key=new GeneratedKeyHolder();
        a.db.update(connection -> {
            PreparedStatement p=connection.prepareStatement("INSERT INTO sys_user(user_name,nick_name,password,type,status,email,phonenumber,sex,del_flag,create_by,create_time) VALUES(?,?,?,'1',?,?,?,?,0,?,CURRENT_TIMESTAMP)",new String[]{"id"});
            p.setString(1,u.userName());p.setString(2,u.nickName());p.setString(3,passwords.encode(u.password()));p.setString(4,u.status());
            p.setString(5,u.email());p.setString(6,u.phonenumber());p.setString(7,u.sex());p.setLong(8,actor);return p;
        },key);
        if (key.getKey()==null) throw new IllegalStateException("Missing generated user id");
        replaceRoles(key.getKey().longValue(),u.roleIds());a.preserveManager(before);
    }
    public void update(long actor,UserInput u) {
        Long id=u==null?null:u.id();if (id==null) throw AdminSupport.invalid();
        tx.executeWithoutResult(status -> {
            a.lock();a.require(actor,"system:user:edit");validate(u,false);a.allowTarget(actor,id);a.allowRoles(actor,u.roleIds());
            int before=a.manageable();revocations.enqueueUser(id);
            // Every profile write advances the revision, independent of database collation.
            a.db.update("UPDATE sys_user SET profile_revision=profile_revision+1,profile_updated_at=CURRENT_TIMESTAMP,nick_name=?,status=?,email=?,phonenumber=?,sex=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
                    u.nickName(),u.status(),u.email(),u.phonenumber(),u.sex(),actor,id);
            replaceRoles(id,u.roleIds());a.preserveManager(before);
        });
        revocations.finishUser(id);
    }
    public void delete(long actor,List<Long> ids) {
        if (ids==null || ids.isEmpty() || ids.size()>100) throw AdminSupport.invalid();
        var distinct=new LinkedHashSet<>(ids);
        tx.executeWithoutResult(status -> {
            a.lock();a.require(actor,"system:user:remove");int before=a.manageable();
            for (Long id:distinct) { if (id==null) throw AdminSupport.invalid();a.allowTarget(actor,id);revocations.enqueueUser(id); }
            for (Long id:distinct) { a.db.update("DELETE FROM sys_user_role WHERE user_id=?",id);a.db.update("DELETE FROM sys_user WHERE id=?",id); }
            a.preserveManager(before);
        });
        boolean failed=false;for (Long id:distinct) { try {revocations.finishUser(id);} catch (RuntimeException e) {failed=true;} }
        if (failed) throw new com.knode.user.auth.AuthenticationFailure(503);
    }
    public void status(long actor,Long id,String value) {
        if (id==null) throw AdminSupport.invalid();AdminSupport.status(value);
        tx.executeWithoutResult(status -> {
            a.lock();a.require(actor,"system:user:edit");a.allowTarget(actor,id);int before=a.manageable();
            revocations.enqueueUser(id);a.db.update("UPDATE sys_user SET status=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",value,actor,id);
            a.preserveManager(before);
        });
        revocations.finishUser(id);
    }
    public void password(long actor,PasswordInput input) {
        if (input==null || input.userId()==null || !StringUtils.hasText(input.password()) || input.password().getBytes(StandardCharsets.UTF_8).length>72) throw AdminSupport.invalid();
        long id=input.userId();
        tx.executeWithoutResult(status -> {
            a.lock();a.require(actor,"system:user:resetPwd");a.allowTarget(actor,id);revocations.enqueueUser(id);
            a.db.update("UPDATE sys_user SET password=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",passwords.encode(input.password()),actor,id);
        });
        revocations.finishUser(id);
    }
}
