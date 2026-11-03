package com.knode.user;

import com.knode.user.account.*;
import com.knode.user.auth.AuthenticationFailure;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only against a newly created, dedicated loopback MySQL instance/schema. */
@EnabledIfSystemProperty(named="knode.it.login.mysql.enabled",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginThrottleMysqlIT {
    private JdbcTemplate db;
    private DriverManagerDataSource ds;
    private LoginAttemptLimiter limiter() {
        return new LoginAttemptLimiter(db,new UserRepository(db),new LoginClientAddress(new MockEnvironment()),
                new DataSourceTransactionManager(ds),() -> db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",(row,n) -> row.getTimestamp(1).getTime()));
    }
    private MockHttpServletRequest request(String ip) {var r=new MockHttpServletRequest();r.setRemoteAddr(ip);return r;}
    @BeforeAll void createFixture() {
        String url=System.getProperty("knode.it.login.mysql.url","");
        URI target=URI.create(url.startsWith("jdbc:")?url.substring(5):"invalid:");
        if (!"mysql".equals(target.getScheme()) || !"127.0.0.1".equals(target.getHost()) || target.getPort()<1024
                || target.getPort()==3306 || target.getPort()>65535 || target.getUserInfo()!=null || target.getQuery()!=null
                || target.getFragment()!=null || !target.getPath().matches("/verify_[a-z0-9_]{1,32}"))
            throw new IllegalArgumentException("New isolated MySQL schema required");
        String user=System.getProperty("knode.it.login.mysql.user");
        if (user==null || user.isBlank()) throw new IllegalArgumentException("Explicit test MySQL user required");
        ds=new DriverManagerDataSource(url,user,System.getProperty("knode.it.login.mysql.password",""));
        db=new JdbcTemplate(ds);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,user_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL UNIQUE)");
        new ResourceDatabasePopulator(new ClassPathResource("db/V009__login_attempt_window.sql")).execute(ds);
        db.update("INSERT INTO sys_user(id,user_name) VALUES(42,?)","\u00c9llen");
    }
    @BeforeEach void clear() {db.update("DELETE FROM user_login_attempt_window");}

    @Test void unicodeCaseAccentAndTrailingSpacesShareTheActualResolvedId() {
        assertEquals(42L,db.queryForObject("SELECT id FROM sys_user WHERE user_name=?",Long.class,"ellen  "));
        var a=limiter();var b=limiter();
        for (int i=0;i<8;i++) {
            String name=i%3==0?"\u00c9llen":i%3==1?"ELLEN  ":"ellen";
            assertEquals(42L,(i%2==0?a:b).check(name,"blog",request("198.51.100."+(i+1))));
        }
        assertThrows(LoginAttemptLimiter.Limited.class,() -> b.check("eLLeN ","blog",request("198.51.100.20")));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_login_attempt_window WHERE bucket LIKE 'blog:u:%'",Integer.class));
    }
    @Test void validSupplementaryCharactersUseTheExistingAccountBucket() {
        String name="\uD83D\uDE03".repeat(33);
        db.update("INSERT INTO sys_user(id,user_name) VALUES(43,?)",name);
        assertEquals(43L,limiter().check(name,"blog",request("198.51.100.31")));
    }
    @Test void repeatedBlockedAttemptsStay429WhenDriverReportsChangedRows() {
        var affected=new DriverManagerDataSource(ds.getUrl(),ds.getUsername(),"");
        var properties=new Properties();properties.setProperty("useAffectedRows","true");
        affected.setConnectionProperties(properties);
        var jdbc=new JdbcTemplate(affected);
        var instance=new LoginAttemptLimiter(jdbc,new UserRepository(jdbc),new LoginClientAddress(new MockEnvironment()),
                new DataSourceTransactionManager(affected),() -> jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",(row,n) -> row.getTimestamp(1).getTime()));
        var req=request("203.0.113.29");
        for (int i=0;i<8;i++) assertEquals(42L,instance.check("ellen","blog",req));
        for (int i=0;i<5;i++) assertThrows(LoginAttemptLimiter.Limited.class,() -> instance.check("ellen","blog",req));
    }
    @Test void independentInstancesEnforceOneLimitUnderConcurrentFirstUse() throws Exception {
        var a=limiter();var b=limiter();var pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        var results=new ArrayList<Future<Integer>>();
        try {
            for (int i=0;i<24;i++) {
                var instance=i%2==0?a:b;
                results.add(pool.submit(() -> {
                    start.await();
                    try {instance.check("ellen","blog",request("203.0.113.7"));return 200;}
                    catch (LoginAttemptLimiter.Limited blocked) {return 429;}
                    catch (AuthenticationFailure unavailable) {return unavailable.status();}
                }));
            }
            start.countDown();var counts=new HashMap<Integer,Integer>();
            for (var result:results) counts.merge(result.get(40,TimeUnit.SECONDS),1,Integer::sum);
            assertEquals(8,counts.getOrDefault(200,0));assertEquals(16,counts.getOrDefault(429,0));assertEquals(0,counts.getOrDefault(503,0));
        } finally {pool.shutdownNow();}
    }
}
