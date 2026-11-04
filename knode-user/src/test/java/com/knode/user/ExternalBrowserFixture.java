package com.knode.user;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic records are inserted only after the external test has verified an empty schema. */
final class ExternalBrowserFixture {
    static final String USER="9007199254740993", ROLE="9007199254740995",
            ROOT="9007199254740997", FIRST="9007199254740999", SECOND="9007199254741001";
    private ExternalBrowserFixture() {}

    static void seed(Connection c,String hash) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(
                "INSERT INTO sys_user(id,user_name,nick_name,password,email,type,status,sex,del_flag) VALUES(?,?,?,?,?,'1','0','2',0)")) {
            p.setLong(1,44);p.setString(2,"it-admin");p.setString(3,"IT Administrator");
            p.setString(4,hash);p.setString(5,"admin@example.invalid");p.executeUpdate();
            p.setLong(1,Long.parseLong(USER));p.setString(2,"it-target");p.setString(3,"IT Target");
            p.setString(5,"target@example.invalid");p.executeUpdate();
        }
        try (Statement s=c.createStatement()) {
            s.executeUpdate("INSERT INTO sys_role(id,role_name,role_key,role_sort,status,del_flag) VALUES"
                    +"(10,'IT operator','it_operator',1,'0','0'),("+ROLE+",'IT target role','it_target',2,'0','0')");
            s.executeUpdate("INSERT INTO sys_user_role(user_id,role_id) VALUES(44,10),("+USER+","+ROLE+")");
            s.executeUpdate("INSERT INTO yan_menu(id,parent_id,menu_name,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,del_flag) VALUES"
                    +"("+ROOT+",0,'IT System',1,'system',NULL,1,'M','0','0',NULL,'system','0'),"
                    +"(101,"+ROOT+",'IT Users',1,'user','system/user/index',1,'C','0','0','system:user:list','user','0'),"
                    +"(102,"+ROOT+",'IT Roles',2,'role','system/role/index',1,'C','0','0','system:role:list','peoples','0'),"
                    +"(103,"+ROOT+",'IT Menus',3,'menu','system/menu/index',1,'C','0','0','system:menu:list','tree-table','0'),"
                    +"(104,"+ROOT+",'IT Operator Permission',4,NULL,NULL,1,'F','0','0','*:*:*',NULL,'0'),"
                    +"("+FIRST+","+ROOT+",'IT First Permission',5,NULL,NULL,1,'F','0','0','it:first',NULL,'0'),"
                    +"("+SECOND+","+ROOT+",'IT Second Permission',6,NULL,NULL,1,'F','0','0','it:second',NULL,'0')");
            s.executeUpdate("INSERT INTO sys_role_menu(role_id,menu_id) VALUES"
                    +"(10,"+ROOT+"),(10,101),(10,102),(10,103),(10,104),(10,"+FIRST+"),(10,"+SECOND+"),"
                    +"("+ROLE+","+FIRST+")");
        }
    }

    static void verify(Connection c) throws SQLException {
        try (Statement s=c.createStatement()) {
            try (ResultSet r=s.executeQuery("SELECT nick_name FROM sys_user WHERE id=42")) {
                assertTrue(r.next());assertEquals("IT Blog Updated",r.getString(1));
            }
            try (ResultSet r=s.executeQuery("SELECT nick_name FROM sys_user WHERE id="+USER)) {
                assertTrue(r.next());assertEquals("IT Target Updated",r.getString(1));
            }
            try (ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sys_user_role WHERE user_id="+USER+" AND role_id="+ROLE)) {
                assertTrue(r.next());assertEquals(1,r.getInt(1));
            }
            try (ResultSet r=s.executeQuery("SELECT role_name FROM sys_role WHERE id="+ROLE)) {
                assertTrue(r.next());assertEquals("IT Target Role Updated",r.getString(1));
            }
            try (ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sys_role_menu WHERE role_id="+ROLE+" AND menu_id IN ("+FIRST+","+SECOND+")")) {
                assertTrue(r.next());assertEquals(2,r.getInt(1));
            }
            try (ResultSet r=s.executeQuery("SELECT menu_name,parent_id FROM yan_menu WHERE id="+ROOT)) {
                assertTrue(r.next());assertEquals("IT System Updated",r.getString(1));assertEquals("0",r.getString(2));
            }
        }
    }
}
