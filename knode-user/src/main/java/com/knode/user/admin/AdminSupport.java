package com.knode.user.admin;

import com.knode.user.account.BusinessFailure;
import com.knode.user.account.UserRepository;
import com.knode.user.auth.AuthenticationFailure;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminSupport {
    final JdbcTemplate db;
    final UserRepository users;
    public AdminSupport(JdbcTemplate db,UserRepository users) { this.db=db;this.users=users; }
    private void requireCurrentSession(long actor) {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet))
            throw new AuthenticationFailure(401);
        Object identity=servlet.getRequest().getAttribute(com.knode.user.web.UserRequestFilter.PRINCIPAL);
        if (!(identity instanceof com.knode.user.auth.UserTokens.Principal principal) || !Long.toString(actor).equals(principal.userId()))
            throw new AuthenticationFailure(401);
        if (db.queryForObject("SELECT COUNT(*) FROM auth_revocation_intent WHERE sid=? AND state='PENDING'",Integer.class,principal.sid())>0)
            throw new AuthenticationFailure(503);
        if (db.queryForObject("SELECT COUNT(*) FROM auth_login_session WHERE sid=? AND user_id=? AND scope='admin' AND active=1 AND expires_at>CURRENT_TIMESTAMP",Integer.class,principal.sid(),actor)!=1)
            throw new AuthenticationFailure(401);
    }
    public void require(long actor,String permission) {
        requireCurrentSession(actor);
        var account=users.byId(actor).filter(UserRepository.Account::active).orElseThrow(() -> new AuthenticationFailure(401));
        if (!"1".equals(account.type())) throw new AuthenticationFailure(403);
        List<String> permissions=users.permissions(actor);
        if (!permissions.contains(permission) && !permissions.contains("*:*:*")) throw new AuthenticationFailure(403);
    }
    public void requireAny(long actor,String... required) {
        requireCurrentSession(actor);
        var account=users.byId(actor).filter(UserRepository.Account::active).orElseThrow(() -> new AuthenticationFailure(401));
        if (!"1".equals(account.type())) throw new AuthenticationFailure(403);
        var permissions=users.permissions(actor);
        if (!permissions.contains("*:*:*") && java.util.Arrays.stream(required).noneMatch(permissions::contains)) throw new AuthenticationFailure(403);
    }
    public boolean wildcard(long actor) { return users.permissions(actor).contains("*:*:*"); }
    public void lock() {
        if (db.query("SELECT id FROM user_security_lock WHERE id=1 FOR UPDATE",(r,n)->r.getInt(1)).size()!=1)
            throw new AuthenticationFailure(503);
    }
    public int manageable() {
        return db.queryForObject("SELECT COUNT(DISTINCT u.id) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.id JOIN yan_menu m ON m.id=rm.menu_id WHERE u.type='1' AND u.status='0' AND u.del_flag=0 AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.del_flag='0' AND m.menu_type IN ('C','F') AND m.perms IN ('system:user:edit','*:*:*')",Integer.class);
    }
    public void preserveManager(int before) {
        if (before>0 && manageable()==0) throw new BusinessFailure(516,"不能移除最后一个可管理账号");
    }
    public Set<Long> actorRoles(long actor) {
        return new HashSet<>(db.query("SELECT r.id FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0'",(r,n)->r.getLong(1),actor));
    }
    public void allowRoles(long actor,Collection<Long> roles) {
        if (roles==null) return;
        if (roles.size()>100) throw invalid();
        Set<Long> mine=actorRoles(actor); boolean all=wildcard(actor);
        for (Long role:new LinkedHashSet<>(roles)) {
            if (role==null || (!all && !mine.contains(role))) throw new AuthenticationFailure(403);
            if (db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id=? AND status='0' AND del_flag='0'",Integer.class,role)!=1) throw invalid();
            if (coreRole(role) && actor!=1L) throw new AuthenticationFailure(403);
        }
    }
    private boolean coreRole(long role) {
        return db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id=? AND (id=1 OR LOWER(role_key) IN ('admin','super_admin','superadmin'))",Integer.class,role)>0;
    }
    public void allowRoleObject(long actor,long role) {
        if (coreRole(role) || (!wildcard(actor) && !actorRoles(actor).contains(role))) throw new AuthenticationFailure(403);
        if (db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id=? AND del_flag='0'",Integer.class,role)!=1) throw invalid();
    }
    public void allowTarget(long actor,long target) {
        if (target==1L || target==actor) throw new AuthenticationFailure(403);
        if (db.query("SELECT id FROM sys_user WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),target).isEmpty()) throw invalid();
        if (db.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND (r.id=1 OR LOWER(r.role_key) IN ('admin','super_admin','superadmin'))",Integer.class,target)>0)
            throw new AuthenticationFailure(403);
        if (!wildcard(actor)) {
            var roles=db.query("SELECT role_id FROM sys_user_role WHERE user_id=?",(r,n)->r.getLong(1),target);
            if (!actorRoles(actor).containsAll(roles)) throw new AuthenticationFailure(403);
        }
    }
    public void allowMenus(long actor,Collection<Long> menus) {
        if (menus==null) return;
        if (menus.size()>500) throw invalid();
        boolean all=wildcard(actor);
        for (Long id:new LinkedHashSet<>(menus)) {
            if (id==null) throw invalid();
            if (db.queryForObject("SELECT COUNT(*) FROM yan_menu WHERE id=? AND status='0' AND del_flag='0'",Integer.class,id)!=1) throw invalid();
            if (!all && db.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND rm.menu_id=?",Integer.class,actor,id)==0)
                throw new AuthenticationFailure(403);
        }
    }
    public void allowMenuObject(long actor,long id) {
        if (db.queryForObject("SELECT COUNT(*) FROM yan_menu WHERE id=? AND del_flag='0'",Integer.class,id)!=1) throw invalid();
        if (db.queryForObject("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id WHERE (r.id=1 OR LOWER(r.role_key) IN ('admin','super_admin','superadmin')) AND rm.menu_id=?",Integer.class,id)>0)
            throw new AuthenticationFailure(403);
        if (!wildcard(actor)) {
            Set<Long> roles=actorRoles(actor);
            if (!roles.containsAll(db.query("SELECT role_id FROM sys_role_menu WHERE menu_id=?",(r,n)->r.getLong(1),id)))
                throw new AuthenticationFailure(403);
            allowMenus(actor,List.of(id));
        }
    }
    public void allowPermission(long actor,String permission) {
        if (permission!=null && !permission.isBlank() && !wildcard(actor) && !users.permissions(actor).contains(permission))
            throw new AuthenticationFailure(403);
    }
    public static BusinessFailure invalid() { return new BusinessFailure(515,"参数不合法"); }
    public static String id(java.sql.ResultSet row,String name) throws java.sql.SQLException {
        long value=row.getLong(name);return row.wasNull()?null:Long.toString(value);
    }
    public static String date(Timestamp time) {
        return time==null?null:time.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    public static int page(Integer n) { return n==null || n<1 ? 1 : n; }
    public static int size(Integer n) { return n==null || n<1 ? 10 : Math.min(n,100); }
    public static long offset(Integer p,Integer s) { return ((long)page(p)-1)*size(s); }
    public static void status(String status) { if (!"0".equals(status) && !"1".equals(status)) throw invalid(); }
}
