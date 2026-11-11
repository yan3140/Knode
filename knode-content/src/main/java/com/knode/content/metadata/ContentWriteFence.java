package com.knode.content.metadata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Must be acquired first inside every article or tag mutation transaction. */
@Component
public class ContentWriteFence {
    private final JdbcTemplate db;
    public ContentWriteFence(JdbcTemplate db) { this.db=db; }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lock() {
        Integer isolation=org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(isolation))
            throw new IllegalStateException("Content writes require READ_COMMITTED");
        db.queryForObject("SELECT id FROM content_write_fence WHERE id=1 FOR UPDATE",Long.class);
    }
}
