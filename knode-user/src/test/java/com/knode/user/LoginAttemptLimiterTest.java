package com.knode.user;

import com.knode.user.account.*;
import com.knode.user.auth.AuthenticationFailure;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {
    private JdbcTemplate db;
    private LoginAttemptLimiter limiter;
    private DataSourceTransactionManager transactions;
    private MutableTestClock clock;
    private MockHttpServletRequest request(String ip) {var r=new MockHttpServletRequest();r.setRemoteAddr(ip);return r;}
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:limit_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);transactions=new DataSourceTransactionManager(ds);clock=new MutableTestClock();
        db.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,user_name VARCHAR(64) UNIQUE)");
        db.execute("CREATE TABLE user_login_attempt_window(bucket VARCHAR(80) PRIMARY KEY,attempts INT NOT NULL,expires_at BIGINT NOT NULL)");
        db.update("INSERT INTO sys_user(id,user_name) VALUES(42,'alice')");
        limiter=new LoginAttemptLimiter(db,new UserRepository(db),new LoginClientAddress(new MockEnvironment()),transactions,clock::millis);
    }
    @Test void eachScopeHasAnExactFixedWindowAndBoundary() {
        var r=request("203.0.113.1");
        for (int i=0;i<5;i++) assertEquals(42L,limiter.check("alice","admin",r));
        assertEquals(300,assertThrows(LoginAttemptLimiter.Limited.class,() -> limiter.check("alice","admin",r)).retryAfter());
        for (int i=0;i<8;i++) assertEquals(42L,limiter.check("alice","blog",r));
        assertThrows(LoginAttemptLimiter.Limited.class,() -> limiter.check("alice","blog",r));
        var start=clock.instant();clock.set(start.plusSeconds(299));
        assertEquals(1,assertThrows(LoginAttemptLimiter.Limited.class,() -> limiter.check("alice","admin",r)).retryAfter());
        clock.set(start.plusSeconds(300));
        assertEquals(42L,limiter.check("alice","admin",r));
    }
    @Test void ipLimitAppliesAcrossDistinctAccounts() {
        for (int i=0;i<21;i++) db.update("INSERT INTO sys_user(id,user_name) VALUES(?,?)",100+i,"user"+i);
        var r=request("203.0.113.2");
        for (int i=0;i<20;i++) assertEquals(100L+i,limiter.check("user"+i,"blog",r));
        assertThrows(LoginAttemptLimiter.Limited.class,() -> limiter.check("user20","blog",r));
    }
    @Test void concurrentNewBucketsCannotExceedTheAccountLimit() throws Exception {
        var pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);var results=new ArrayList<Future<Integer>>();
        try {
            for (int i=0;i<24;i++) results.add(pool.submit(() -> {
                start.await();
                try {limiter.check("alice","blog",request("198.51.100.1"));return 200;}
                catch (LoginAttemptLimiter.Limited limited) {return 429;}
                catch (AuthenticationFailure unavailable) {return unavailable.status();}
            }));
            start.countDown();var counts=new HashMap<Integer,Integer>();
            for (var result:results) counts.merge(result.get(30,TimeUnit.SECONDS),1,Integer::sum);
            assertEquals(8,counts.getOrDefault(200,0));assertEquals(16,counts.getOrDefault(429,0));assertEquals(0,counts.getOrDefault(503,0));
        } finally {pool.shutdownNow();}
    }
    @Test void countersSurviveOuterRollbackAndMissingTableFailsClosed() {
        var outer=new TransactionTemplate(transactions);
        outer.executeWithoutResult(status -> {limiter.check("alice","blog",request("203.0.113.3"));status.setRollbackOnly();});
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_login_attempt_window WHERE attempts=1",Integer.class));
        db.execute("DROP TABLE user_login_attempt_window");
        assertEquals(503,assertThrows(AuthenticationFailure.class,() -> limiter.check("alice","blog",request("203.0.113.3"))).status());
    }
    @Test void cleanupIsBoundedAndRechecksExpiry() {
        long now=clock.millis();
        for (int i=0;i<102;i++) db.update("INSERT INTO user_login_attempt_window(bucket,attempts,expires_at) VALUES(?,1,?)","expired"+i,now-1);
        db.update("INSERT INTO user_login_attempt_window(bucket,attempts,expires_at) VALUES('active',1,?)",now+300000);
        assertEquals(100,limiter.cleanup(100));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM user_login_attempt_window",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_login_attempt_window WHERE bucket='active'",Integer.class));
    }
}
