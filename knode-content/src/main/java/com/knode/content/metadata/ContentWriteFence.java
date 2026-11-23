package com.knode.content.metadata;

import java.sql.Connection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Take this row lock before any article projection mutation. The database releases it at transaction end. */
@Component
public class ContentWriteFence {
    private final JdbcTemplate db;
    public ContentWriteFence(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Content writes require READ_COMMITTED");
        }
        if (!held()) {
            db.queryForObject("SELECT id FROM content_write_fence WHERE id=1 FOR UPDATE", Long.class);
            TransactionSynchronizationManager.registerSynchronization(new Held());
        }
    }

    public void requireHeld() {
        if (!held()) {
            throw new IllegalStateException("The content write fence must be acquired first");
        }
    }

    private static boolean held() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(Held.class::isInstance);
    }

    private static final class Held implements TransactionSynchronization {}
}
