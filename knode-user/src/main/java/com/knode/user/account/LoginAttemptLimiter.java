package com.knode.user.account;

import com.knode.user.auth.AuthenticationFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Database-backed fixed windows, committed before the login transaction. */
@Service
public final class LoginAttemptLimiter {
    private static final long WINDOW_MS=300_000L;
    private final JdbcTemplate db;
    private final UserRepository users;
    private final LoginClientAddress addresses;
    private final TransactionTemplate independent;
    private final LoginLimitTime time;
    public LoginAttemptLimiter(JdbcTemplate db,UserRepository users,LoginClientAddress addresses,
                               PlatformTransactionManager transactions,LoginLimitTime time) {
        this.db=db;this.users=users;this.addresses=addresses;this.time=time;
        independent=new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Long check(String username,String scope,HttpServletRequest request) {
        if (!Set.of("admin","blog").contains(scope)) throw new IllegalArgumentException("Invalid login scope");
        byte[] ip=addresses.resolve(request);
        try {
            Long id=username!=null && username.length()<=64?users.loginIdentity(username).orElse(null):null;
            String account=id==null?"unknown:"+unknownBucket(username):"id:"+id;
            String ipKey=scope+":i:"+hex(digest(ip));
            String userKey=scope+":u:"+hex(digest(account.getBytes(StandardCharsets.UTF_8)));
            int ipLimit="admin".equals(scope)?15:20,userLimit="admin".equals(scope)?5:8;
            long now=time.millis();
            Result outcome=null;
            for (int attempt=0;attempt<4;attempt++) {
                try {
                    outcome=independent.execute(status -> {
                        // All concurrent attempts acquire IP before account. No user row lock is held here.
                        Window ipWindow=increment(ipKey,ipLimit,now);
                        Window userWindow=increment(userKey,userLimit,now);
                        return new Result(ipWindow,userWindow);
                    });
                    break;
                } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
                    if (attempt==3) throw retryable;
                }
            }
            if (outcome==null) throw new AuthenticationFailure(503);
            long wait=Math.max(outcome.ip().attempts()>ipLimit?outcome.ip().expiresAt()-now:0,
                    outcome.user().attempts()>userLimit?outcome.user().expiresAt()-now:0);
            if (wait>0) throw new Limited(Math.max(1,(wait+999)/1000));
            return id;
        } catch (DataAccessException | TransactionException failure) {throw new AuthenticationFailure(503);}
    }
    private Window increment(String bucket,int limit,long now) {
        int updated=update(bucket,limit,now);
        if (updated==0) {
            try {db.update("INSERT INTO user_login_attempt_window(bucket,attempts,expires_at) VALUES(?,1,?)",bucket,now+WINDOW_MS);}
            catch (DuplicateKeyException raced) {
                if (update(bucket,limit,now)!=1) throw new DataRetrievalFailureException("Login limit unavailable");
            }
        } else if (updated!=1) throw new DataRetrievalFailureException("Login limit unavailable");
        return db.queryForObject("SELECT attempts,expires_at FROM user_login_attempt_window WHERE bucket=?",
                (row,n) -> new Window(row.getInt(1),row.getLong(2)),bucket);
    }
    private int update(String bucket,int limit,long now) {
        return db.update("UPDATE user_login_attempt_window SET attempts=CASE WHEN expires_at<=? THEN 1 WHEN attempts<? THEN attempts+1 ELSE attempts END, expires_at=CASE WHEN expires_at<=? THEN ? ELSE expires_at END WHERE bucket=?",
                now,limit+1,now,now+WINDOW_MS,bucket);
    }
    private int unknownBucket(String username) {
        String name=username==null?"":username;
        if (name.length()>256) name=name.substring(0,256);
        name=java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        return digest(name.getBytes(StandardCharsets.UTF_8))[0]&255;
    }
    private static byte[] digest(byte[] value) {
        try {return MessageDigest.getInstance("SHA-256").digest(value);}
        catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String hex(byte[] value) {return HexFormat.of().formatHex(value);}
    private record Window(int attempts,long expiresAt) {}
    private record Result(Window ip,Window user) {}
    public static final class Limited extends RuntimeException {
        private final long retryAfter;
        Limited(long retryAfter) {this.retryAfter=retryAfter;}
        public long retryAfter() {return retryAfter;}
    }
    /** Delete at most 100 expired rows per pass; the recheck prevents deleting a renewed bucket. */
    public int cleanup(int limit) {
        if (limit<1 || limit>100) throw new IllegalArgumentException("Invalid cleanup limit");
        long now=time.millis();
        List<String> keys=db.queryForList("SELECT bucket FROM user_login_attempt_window WHERE expires_at<=? ORDER BY expires_at,bucket LIMIT ?",String.class,now,limit);
        int deleted=0;
        for (String key:keys) deleted+=db.update("DELETE FROM user_login_attempt_window WHERE bucket=? AND expires_at<=?",key,now);
        return deleted;
    }
    @Scheduled(scheduler="sessionEventScheduler",fixedDelayString="${USER_LOGIN_LIMIT_CLEANUP_POLL_MS:60000}",initialDelayString="${USER_LOGIN_LIMIT_CLEANUP_INITIAL_MS:60000}")
    public void cleanupPoll() {try {cleanup(100);} catch (DataAccessException ignored) { /* Retry next bounded pass. */ }}
}
