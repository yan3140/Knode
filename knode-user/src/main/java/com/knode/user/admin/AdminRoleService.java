package com.knode.user.admin;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import static com.knode.user.admin.AdminDtos.*;

@Service
public class AdminRoleService {
    private final AdminSupport a;
    public AdminRoleService(AdminSupport a) { this.a=a; }
    private RoleView view(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new RoleView(Long.toString(r.getLong("id")),r.getString("role_name"),r.getString("role_key"),r.getInt("role_sort"),r.getString("status"),r.getString("del_flag"),
                AdminSupport.id(r,"create_by"),AdminSupport.date(r.getTimestamp("create_time")),AdminSupport.id(r,"update_by"),AdminSupport.date(r.getTimestamp("update_time")),r.getString("remark"),null);
    }
    private List<String> menuIds(long id) {
        return a.db.query("SELECT menu_id FROM sys_role_menu WHERE role_id=? ORDER BY menu_id",(r,n)->Long.toString(r.getLong(1)),id);
    }
    public Page list(long actor,Integer page,Integer size,String name,String key,String status) {
        a.require(actor,"system:role:list");
        StringBuilder where=new StringBuilder(" WHERE del_flag='0'");List<Object> args=new ArrayList<>();
        if (StringUtils.hasText(name)) {where.append(" AND role_name LIKE ?");args.add("%"+name+"%");}
        if (StringUtils.hasText(key)) {where.append(" AND role_key LIKE ?");args.add("%"+key+"%");}
        if (StringUtils.hasText(status)) {AdminSupport.status(status);where.append(" AND status=?");args.add(status);}
        Long total=a.db.queryForObject("SELECT COUNT(*) FROM sys_role"+where,Long.class,args.toArray());
        var parameters=new ArrayList<>(args);parameters.add(AdminSupport.size(size));parameters.add(AdminSupport.offset(page,size));
        var rows=a.db.query("SELECT * FROM sys_role"+where+" ORDER BY role_sort,id LIMIT ? OFFSET ?",this::view,parameters.toArray());
        return new Page(rows,Long.toString(total));
    }
    public List<RoleView> all(long actor) {
        a.requireAny(actor,"system:role:list","system:user:query","system:user:add","system:user:edit");
        return a.db.query("SELECT * FROM sys_role WHERE status='0' AND del_flag='0' ORDER BY role_sort,id",this::view);
    }
    public RoleView detail(long actor,long id) {
        a.require(actor,"system:role:query");
        var rows=a.db.query("SELECT * FROM sys_role WHERE id=? AND del_flag='0'",this::view,id);
        if (rows.isEmpty()) throw AdminSupport.invalid();
        var r=rows.get(0);
        return new RoleView(r.id(),r.roleName(),r.roleKey(),r.roleSort(),r.status(),r.delFlag(),r.createBy(),r.createTime(),r.updateBy(),r.updateTime(),r.remark(),menuIds(id));
    }
    private void validate(RoleInput r) {
        if (r==null || !StringUtils.hasText(r.roleName()) || r.roleName().length()>128
                || !StringUtils.hasText(r.roleKey()) || r.roleKey().length()>128 || r.roleSort()==null
                || (r.remark()!=null && r.remark().length()>500)) throw AdminSupport.invalid();
        AdminSupport.status(r.status());
        // Permit only canonical ASCII identifiers so collation cannot fold accents, case or padding into a core role.
        if (!r.roleKey().matches("[a-z][a-z0-9:_-]{0,127}")) throw AdminSupport.invalid();
        if (Set.of("admin","superadmin").contains(r.roleKey().replaceAll("[-_:]","")))
            throw new com.knode.user.auth.AuthenticationFailure(403);
    }
    private void replaceMenus(long id,List<Long> menuIds) {
        a.db.update("DELETE FROM sys_role_menu WHERE role_id=?",id);
        if (menuIds!=null) for (Long menu:new LinkedHashSet<>(menuIds)) a.db.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",id,menu);
    }
    @Transactional public void add(long actor,RoleInput input) {
        a.lock();a.require(actor,"system:role:add");validate(input);a.allowMenus(actor,input.menuIds());int before=a.manageable();
        if (a.db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_key=? AND del_flag='0'",Integer.class,input.roleKey())>0) throw AdminSupport.invalid();
        var key=new GeneratedKeyHolder();
        a.db.update(c -> {
            PreparedStatement p=c.prepareStatement("INSERT INTO sys_role(role_name,role_key,role_sort,status,remark,del_flag,create_by,create_time) VALUES(?,?,?,?,?,'0',?,CURRENT_TIMESTAMP)",new String[]{"id"});
            p.setString(1,input.roleName());p.setString(2,input.roleKey());p.setInt(3,input.roleSort());p.setString(4,input.status());p.setString(5,input.remark());p.setLong(6,actor);return p;
        },key);
        if (key.getKey()==null) throw new IllegalStateException("Missing generated role id");
        replaceMenus(key.getKey().longValue(),input.menuIds());a.preserveManager(before);
    }
    @Transactional public void update(long actor,RoleInput input) {
        if (input==null || input.id()==null) throw AdminSupport.invalid();
        a.lock();a.require(actor,"system:role:edit");validate(input);a.allowRoleObject(actor,input.id());a.allowMenus(actor,input.menuIds());int before=a.manageable();
        if (a.db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_key=? AND id<>? AND del_flag='0'",Integer.class,input.roleKey(),input.id())>0) throw AdminSupport.invalid();
        a.db.update("UPDATE sys_role SET role_name=?,role_key=?,role_sort=?,status=?,remark=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",input.roleName(),input.roleKey(),input.roleSort(),input.status(),input.remark(),actor,input.id());
        replaceMenus(input.id(),input.menuIds());a.preserveManager(before);
    }
    @Transactional public void delete(long actor,List<Long> ids) {
        if (ids==null || ids.isEmpty() || ids.size()>100) throw AdminSupport.invalid();
        a.lock();a.require(actor,"system:role:remove");int before=a.manageable();
        for (Long id:new LinkedHashSet<>(ids)) {if (id==null) throw AdminSupport.invalid();a.allowRoleObject(actor,id);}
        for (Long id:new LinkedHashSet<>(ids)) {
            a.db.update("DELETE FROM sys_role_menu WHERE role_id=?",id);a.db.update("DELETE FROM sys_user_role WHERE role_id=?",id);a.db.update("DELETE FROM sys_role WHERE id=?",id);
        }
        a.preserveManager(before);
    }
    @Transactional public void status(long actor,Long id,String status) {
        if (id==null) throw AdminSupport.invalid();AdminSupport.status(status);
        a.lock();a.require(actor,"system:role:edit");a.allowRoleObject(actor,id);int before=a.manageable();
        a.db.update("UPDATE sys_role SET status=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",status,actor,id);
        a.preserveManager(before);
    }
}
