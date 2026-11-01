package com.knode.user;

import com.knode.auth.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only receiver: the run CAS and idempotency record share one local transaction. */
final class LocalCheckpointReceiverFixture implements CheckpointCommitGuard {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    LocalCheckpointReceiverFixture() {this("run-1","1","worker-1");}
    LocalCheckpointReceiverFixture(String run,String epoch,String worker) {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE receiver_run(run_id VARCHAR(64) PRIMARY KEY, epoch VARCHAR(19), worker VARCHAR(64), effects INT)");
        db.execute("CREATE TABLE receiver_checkpoint(operation_id VARCHAR(64) PRIMARY KEY, jti VARCHAR(36) UNIQUE, digest VARCHAR(64))");
        db.update("INSERT INTO receiver_run VALUES(?,?,?,0)",run,epoch,worker);
    }
    @Override public Outcome commit(DelegationTokenVerifier.AuthorizedCheckpoint c,String digest) {
        if (c==null || digest==null || !digest.matches("[0-9a-f]{64}")) throw new AuthFailure(403);
        return tx.execute(status -> {
            var r=c.resource();
            var rows=db.queryForList("SELECT epoch,worker FROM receiver_run WHERE run_id=? FOR UPDATE",r.get("runId"));
            if (rows.size()!=1 || !Objects.equals(rows.get(0).get("epoch"),r.get("controlEpoch"))
                    || !Objects.equals(rows.get(0).get("worker"),r.get("workerId"))) throw new AuthFailure(403);
            var existing=db.queryForList("SELECT jti,digest FROM receiver_checkpoint WHERE operation_id=?",r.get("checkpointOperationId"));
            if (!existing.isEmpty()) {
                var e=existing.get(0);
                if (!Objects.equals(e.get("jti"),c.jti()) || !Objects.equals(e.get("digest"),digest)) throw new AuthFailure(403);
                return Outcome.ALREADY_APPLIED;
            }
            int changed=db.update("UPDATE receiver_run SET effects=effects+1 WHERE run_id=? AND epoch=? AND worker=?",
                    r.get("runId"),r.get("controlEpoch"),r.get("workerId"));
            if (changed!=1) throw new AuthFailure(403);
            db.update("INSERT INTO receiver_checkpoint VALUES(?,?,?)",r.get("checkpointOperationId"),c.jti(),digest);
            return Outcome.APPLIED;
        });
    }
}
