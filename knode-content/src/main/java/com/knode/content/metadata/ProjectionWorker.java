package com.knode.content.metadata;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectionWorker {
    private final JdbcTemplate db;
    private final ProjectionTransaction tx;
    private record Target(String job,long articleId) {}
    public ProjectionWorker(JdbcTemplate db,ProjectionTransaction tx) { this.db=db; this.tx=tx; }

    public int runBatch(int batchSize) {
        if (batchSize<1 || batchSize>100) throw new IllegalArgumentException("Invalid metadata batch size");
        List<Target> due=db.query("SELECT job_id,article_id FROM content_metadata_target WHERE state='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6) ORDER BY next_attempt,job_id,article_id LIMIT ?",
            (r,n) -> new Target(r.getString(1),r.getLong(2)),batchSize);
        int completed=0;
        for (Target target:due) {
            try { if (tx.process(target.job(),target.articleId())) completed++; }
            catch (RuntimeException failure) {
                try { tx.recordFailure(target.job(),target.articleId()); }
                catch (RuntimeException unavailable) { throw new IllegalStateException("Metadata recovery unavailable"); }
            }
        }
        return completed;
    }
}
