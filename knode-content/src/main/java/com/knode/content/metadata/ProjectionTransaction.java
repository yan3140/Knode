package com.knode.content.metadata;

import com.knode.content.article.ArticleCommandService;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectionTransaction {
    private final JdbcTemplate db;
    private final ContentWriteFence fence;
    private final ArticleCommandService articles;
    public ProjectionTransaction(JdbcTemplate db,ContentWriteFence fence,ArticleCommandService articles) {
        this.db=db; this.fence=fence; this.articles=articles;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public boolean process(String job,long articleId) {
        fence.lock();
        List<Integer> attempts=db.queryForList("SELECT attempts FROM content_metadata_target WHERE job_id=? AND article_id=? AND state='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6) FOR UPDATE",Integer.class,job,articleId);
        if (attempts.isEmpty()) return false;
        articles.refreshMetadataProjection(articleId);
        db.update("UPDATE content_metadata_target SET state='DONE',error_code=NULL WHERE job_id=? AND article_id=?",job,articleId);
        return true;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void recordFailure(String job,long articleId) {
        fence.lock();
        List<Integer> attempts=db.queryForList("SELECT attempts FROM content_metadata_target WHERE job_id=? AND article_id=? AND state='PENDING' FOR UPDATE",Integer.class,job,articleId);
        if (attempts.isEmpty()) return;
        int next=attempts.get(0)==Integer.MAX_VALUE?Integer.MAX_VALUE:attempts.get(0)+1;
        long seconds=Math.min(3600L,1L<<Math.min(next,12));
        db.update("UPDATE content_metadata_target SET attempts=?,next_attempt=?,error_code='PROCESSING_FAILURE' WHERE job_id=? AND article_id=? AND state='PENDING'",
            next,Timestamp.from(java.util.Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class)).toInstant().plusSeconds(seconds)),job,articleId);
    }
}
