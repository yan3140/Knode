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
public class AdminMenuService {
    private final AdminSupport a;
    public AdminMenuService(AdminSupport a) { this.a=a; }
    private MenuView view(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new MenuView(Long.toString(r.getLong("id")),r.getString("menu_name"),Long.toString(r.getLong("parent_id")),r.getInt("order_num"),
                r.getString("path"),r.getString("component"),(Integer)r.getObject("is_frame"),r.getString("menu_type"),r.getString("visible"),
                r.getString("status"),r.getString("perms"),r.getString("icon"),AdminSupport.id(r,"create_by"),AdminSupport.date(r.getTimestamp("create_time")),
                AdminSupport.id(r,"update_by"),AdminSupport.date(r.getTimestamp("update_time")),r.getString("remark"),r.getString("del_flag"));
    }
    public List<MenuView> list(long actor,String name,String status) {
        a.require(actor,"system:menu:list");
        StringBuilder where=new StringBuilder(" WHERE del_flag='0'");List<Object> args=new ArrayList<>();
        if (StringUtils.hasText(name)) { where.append(" AND menu_name LIKE ?");args.add("%"+name+"%"); }
        if (StringUtils.hasText(status)) {AdminSupport.status(status);where.append(" AND status=?");args.add(status);}
        return a.db.query("SELECT * FROM yan_menu"+where+" ORDER BY parent_id,order_num,id",this::view,args.toArray());
    }
    public MenuView detail(long actor,long id) {
        a.require(actor,"system:menu:query");
        var rows=a.db.query("SELECT * FROM yan_menu WHERE id=? AND del_flag='0'",this::view,id);
        if (rows.isEmpty()) throw AdminSupport.invalid();
        return rows.get(0);
    }
    private List<Tree> build(String parent,List<MenuView> menus,Set<String> ancestors) {
        var result=new ArrayList<Tree>();
        for (var menu:menus) {
            if (!menu.parentId().equals(parent) || ancestors.contains(menu.id())) continue;
            var next=new HashSet<>(ancestors);next.add(menu.id());
            result.add(new Tree(menu.id(),menu.menuName(),build(menu.id(),menus,next)));
        }
        return result;
    }
    private List<Tree> allTrees() {
        return build("0",a.db.query("SELECT * FROM yan_menu WHERE del_flag='0' ORDER BY order_num,id",this::view),new HashSet<>());
    }
    public List<Tree> tree(long actor) {
        a.requireAny(actor,"system:menu:list","system:role:query","system:role:add","system:role:edit");return allTrees();
    }
    public Map<String,Object> roleTree(long actor,long roleId) {
        a.requireAny(actor,"system:role:query","system:role:edit","system:menu:list");
        if (a.db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE id=? AND del_flag='0'",Integer.class,roleId)!=1) throw AdminSupport.invalid();
        var checked=a.db.query("SELECT menu_id FROM sys_role_menu WHERE role_id=? ORDER BY menu_id",(r,n)->Long.toString(r.getLong(1)),roleId);
        return Map.of("menus",allTrees(),"checkedKeys",checked);
    }
    private void validate(MenuInput m) {
        if (m==null || !StringUtils.hasText(m.menuName()) || m.menuName().length()>128 || m.parentId()==null || m.parentId()<0
                || m.orderNum()==null || m.menuType()==null || !Set.of("M","C","F").contains(m.menuType()) || m.visible()==null || !Set.of("0","1").contains(m.visible())
                || (m.path()!=null && m.path().length()>255) || (m.component()!=null && m.component().length()>255)
                || (m.perms()!=null && m.perms().length()>255) || (m.icon()!=null && m.icon().length()>255)
                || (m.remark()!=null && m.remark().length()>500)) throw AdminSupport.invalid();
        AdminSupport.status(m.status());
        if (m.isFrame()!=null && m.isFrame()!=0 && m.isFrame()!=1) throw AdminSupport.invalid();
    }
    private void checkParent(long id,long parent) {
        Set<Long> seen=new HashSet<>();seen.add(id);
        while (parent!=0) {
            if (!seen.add(parent) || seen.size()>512) throw AdminSupport.invalid();
            var rows=a.db.query("SELECT parent_id FROM yan_menu WHERE id=? AND del_flag='0'",(r,n)->r.getLong(1),parent);
            if (rows.size()!=1) throw AdminSupport.invalid();parent=rows.get(0);
        }
    }
    @Transactional public void add(long actor,MenuInput m) {
        a.lock();a.require(actor,"system:menu:add");validate(m);a.allowPermission(actor,m.perms());checkParent(-1,m.parentId());
        int before=a.manageable();
        a.db.update("INSERT INTO yan_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,remark,del_flag,create_by,create_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'0',?,CURRENT_TIMESTAMP)",
                m.menuName(),m.parentId(),m.orderNum(),m.path(),m.component(),m.isFrame(),m.menuType(),m.visible(),m.status(),m.perms(),m.icon(),m.remark(),actor);
        a.preserveManager(before);
    }
    @Transactional public void update(long actor,MenuInput m) {
        if (m==null || m.id()==null) throw AdminSupport.invalid();
        a.lock();a.require(actor,"system:menu:edit");validate(m);a.allowMenuObject(actor,m.id());a.allowPermission(actor,m.perms());checkParent(m.id(),m.parentId());
        int before=a.manageable();
        a.db.update("UPDATE yan_menu SET menu_name=?,parent_id=?,order_num=?,path=?,component=?,is_frame=?,menu_type=?,visible=?,status=?,perms=?,icon=?,remark=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
                m.menuName(),m.parentId(),m.orderNum(),m.path(),m.component(),m.isFrame(),m.menuType(),m.visible(),m.status(),m.perms(),m.icon(),m.remark(),actor,m.id());
        a.preserveManager(before);
    }
    @Transactional public void delete(long actor,long id) {
        a.lock();a.require(actor,"system:menu:remove");a.allowMenuObject(actor,id);int before=a.manageable();
        if (a.db.queryForObject("SELECT COUNT(*) FROM yan_menu WHERE parent_id=? AND del_flag='0'",Integer.class,id)>0) throw AdminSupport.invalid();
        a.db.update("DELETE FROM sys_role_menu WHERE menu_id=?",id);a.db.update("DELETE FROM yan_menu WHERE id=?",id);
        a.preserveManager(before);
    }
}
