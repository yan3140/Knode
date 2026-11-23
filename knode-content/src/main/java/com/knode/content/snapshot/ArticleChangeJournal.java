package com.knode.content.snapshot;

import com.knode.content.metadata.ContentWriteFence;
import com.knode.content.relay.EventGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ArticleChangeJournal {
    static final int MAX_FACTS_PER_COMMIT = 100;
    private final JdbcTemplate db;
    private final ContentWriteFence fence;

    public ArticleChangeJournal(JdbcTemplate db, ContentWriteFence fence) {
        this.db = db;
        this.fence = fence;
    }

    public enum Family { ARTICLE, COUNTER }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Family family, String type, String eventId, int bytes) {
        fence.requireHeld();
        if (bytes <= 0 || bytes > EventGuard.MAX_BYTES) {
            throw new IllegalStateException("Invalid change fact size");
        }
        Commit commit = TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(Commit.class::isInstance).map(Commit.class::cast).findFirst().orElseGet(this::startCommit);
        int position = commit.count + 1;
        int totalBytes = commit.bytes + bytes;
        if (position > MAX_FACTS_PER_COMMIT || totalBytes > EventGuard.MAX_BYTES) {
            throw new IllegalStateException("Article change transaction exceeds batch budget");
        }
        db.update("""
                INSERT INTO content_article_change_fact(commit_sequence,fact_position,event_id,family,event_type,payload_bytes)
                VALUES(?,?,?,?,?,?)
                """, commit.sequence, position, eventId, family.name(), type, bytes);
        db.update("UPDATE content_article_change_batch SET fact_count=?,payload_bytes=? WHERE commit_sequence=?",
                position, totalBytes, commit.sequence);
        commit.count = position;
        commit.bytes = totalBytes;
    }

    private Commit startCommit() {
        // The fence serializes transactions through commit. A rollback undoes both the head and the references.
        if (db.update("""
                UPDATE content_article_change_head SET last_sequence=last_sequence+1
                WHERE id=1 AND last_sequence<?
                """, Long.MAX_VALUE) != 1) {
            throw new IllegalStateException("Article change sequence unavailable");
        }
        Commit commit = new Commit(currentHead());
        db.update("INSERT INTO content_article_change_batch(commit_sequence,fact_count,payload_bytes) VALUES(?,0,0)",
                commit.sequence);
        // Spring suspends this marker with its transaction, including for a REQUIRES_NEW call.
        TransactionSynchronizationManager.registerSynchronization(commit);
        return commit;
    }

    long currentHead() {
        return db.queryForObject("SELECT last_sequence FROM content_article_change_head WHERE id=1", Long.class);
    }

    private static final class Commit implements TransactionSynchronization {
        private final long sequence;
        private int count;
        private int bytes;

        private Commit(long sequence) {
            this.sequence = sequence;
        }
    }
}
