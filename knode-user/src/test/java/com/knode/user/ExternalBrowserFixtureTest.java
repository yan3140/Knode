package com.knode.user;

import com.knode.user.account.UserRepository;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserFixtureTest {
    @Test void seedProducesUsableDynamicMenusAndLongRelations() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:browser_fixture;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(ds);
        try (Connection c=ds.getConnection()) {ExternalBrowserFixture.seed(c,"fixture-hash");}
        var jdbc=new JdbcTemplate(ds);var users=new UserRepository(jdbc);
        assertEquals("IT Administrator",users.byId(44).orElseThrow().nickName());
        assertTrue(users.permissions(44).contains("*:*:*"));
        var menus=users.routers(44);
        assertEquals(1,menus.size());assertEquals(ExternalBrowserFixture.ROOT,menus.get(0).id());
        assertEquals("0",menus.get(0).parentId());assertEquals("system",menus.get(0).path());
        assertEquals(3,menus.get(0).children().size());
        assertEquals("system/user/index",menus.get(0).children().get(0).component());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",Integer.class,
                Long.parseLong(ExternalBrowserFixture.USER),Long.parseLong(ExternalBrowserFixture.ROLE)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=? AND menu_id=?",Integer.class,
                Long.parseLong(ExternalBrowserFixture.ROLE),Long.parseLong(ExternalBrowserFixture.FIRST)));
    }
}
