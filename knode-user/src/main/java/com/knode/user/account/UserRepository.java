package com.knode.user.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    public UserRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Account(long id,String userName,String nickName,String password,String type,String status,
                          String email,String sex,String avatar,int delFlag) {
        public boolean active() { return "0".equals(status) && delFlag==0; }
        public UserInfo info() { return new UserInfo(Long.toString(id),nickName,avatar,sex,email); }
    }
    public record UserInfo(String id,String nickName,String avatar,String sex,String email) {}
    public record Menu(String id,String parentId,String menuName,int orderNum,String path,String component,
                       String menuType,String visible,String status,String perms,String icon,String createTime,
                       List<Menu> children) {}
    private Account account(ResultSet r,int row) throws SQLException {
        return new Account(r.getLong("id"),r.getString("user_name"),r.getString("nick_name"),r.getString("password"),
                r.getString("type"),r.getString("status"),r.getString("email"),r.getString("sex"),r.getString("avatar"),r.getInt("del_flag"));
    }
    public Optional<Account> byUsername(String name) {
        return jdbc.query("SELECT * FROM sys_user WHERE user_name=?",this::account,name).stream().findFirst();
    }
    public Optional<Account> byUsernameForUpdate(String name) {
        return jdbc.query("SELECT * FROM sys_user WHERE user_name=? FOR UPDATE",this::account,name).stream().findFirst();
    }
    public Optional<Account> byId(long id) {
        return jdbc.query("SELECT * FROM sys_user WHERE id=?",this::account,id).stream().findFirst();
    }
    public boolean exists(String column,String value) {
        if (!Set.of("user_name","nick_name","email").contains(column)) throw new IllegalArgumentException("column");
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE "+column+"=?",Integer.class,value)>0;
    }
    public void insert(String username,String nickname,String password,String email) {
        jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,email,type,status,sex,del_flag,create_time) VALUES (?,?,?,?,'0','0','2',0,CURRENT_TIMESTAMP)",username,nickname,password,email);
    }
    public int updateProfile(long id,String nickname,String avatar,String sex) {
        return jdbc.update("UPDATE sys_user SET nick_name=?,avatar=?,sex=?,update_by=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND status='0' AND del_flag=0",nickname,avatar,sex,id,id);
    }
    public List<String> roles(long id) {
        return jdbc.queryForList("SELECT DISTINCT r.role_key FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' ORDER BY r.role_key",String.class,id);
    }
    public List<String> permissions(long id) {
        return jdbc.queryForList("SELECT DISTINCT m.perms FROM yan_menu m JOIN sys_role_menu rm ON rm.menu_id=m.id JOIN sys_role r ON r.id=rm.role_id JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.del_flag='0' AND m.menu_type IN ('C','F') AND m.perms IS NOT NULL AND m.perms<>'' ORDER BY m.perms",String.class,id);
    }
    public List<Menu> routers(long id) {
        var flat=jdbc.query("SELECT DISTINCT m.* FROM yan_menu m JOIN sys_role_menu rm ON rm.menu_id=m.id JOIN sys_role r ON r.id=rm.role_id JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.del_flag='0' AND m.menu_type IN ('M','C') ORDER BY m.order_num,m.id",(r,n) -> {
            var time=r.getTimestamp("create_time");
            return new Menu(Long.toString(r.getLong("id")),Long.toString(r.getLong("parent_id")),r.getString("menu_name"),r.getInt("order_num"),
                    r.getString("path"),r.getString("component"),r.getString("menu_type"),r.getString("visible"),r.getString("status"),
                    r.getString("perms"),r.getString("icon"),time==null?null:time.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),List.of());
        },id);
        return build("0",flat,new HashSet<>());
    }
    private List<Menu> build(String parent,List<Menu> flat,Set<String> ancestors) {
        var result=new ArrayList<Menu>();
        for (Menu m:flat) {
            if (!m.parentId().equals(parent) || ancestors.contains(m.id())) continue;
            var next=new HashSet<>(ancestors); next.add(m.id());
            result.add(new Menu(m.id(),m.parentId(),m.menuName(),m.orderNum(),m.path(),m.component(),m.menuType(),
                    m.visible(),m.status(),m.perms(),m.icon(),m.createTime(),build(m.id(),flat,next)));
        }
        return result;
    }
}
