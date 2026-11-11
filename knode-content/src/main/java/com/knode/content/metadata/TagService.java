package com.knode.content.metadata;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {
    private final JdbcTemplate db;
    private final ContentWriteFence fence;
    public TagService(JdbcTemplate db,ContentWriteFence fence) { this.db=db; this.fence=fence; }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void create(String actor,TagInput input) {
        fence.lock();
        if (!com.knode.auth.UserTokenVerifier.validUserId(actor)) throw new MetadataFailure(515);
        if (input==null || blank(input.name()) || blank(input.remark())) throw new MetadataFailure(506);
        if (input.name().length()>256 || input.remark().length()>1024) throw new MetadataFailure(515);
        Timestamp now=Timestamp.from(Instant.now());
        db.update("INSERT INTO content_tag(name,remark,del_flag,created_by,created_at,updated_by,updated_at) VALUES(?,?,0,?,?,?,?)",
            input.name(),input.remark(),actor,now,actor,now);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void update(String actor,TagInput input) {
        fence.lock();
        if (!com.knode.auth.UserTokenVerifier.validUserId(actor)) throw new MetadataFailure(515);
        if (input==null || input.id()==null || input.id()<=0) throw new MetadataFailure(515);
        boolean hasName=!blank(input.name()), hasRemark=!blank(input.remark());
        if ((!hasName && !hasRemark) || hasName && input.name().length()>256 || hasRemark && input.remark().length()>1024)
            throw new MetadataFailure(515);
        Map<String,Object> old=locked(input.id());
        String oldName=(String)old.get("NAME");
        String nextName=hasName?input.name():oldName;
        String nextRemark=hasRemark?input.remark():(String)old.get("REMARK");
        db.update("UPDATE content_tag SET name=?,remark=?,updated_by=?,updated_at=? WHERE id=?",
            nextName,nextRemark,actor,Timestamp.from(Instant.now()),input.id());
        if (!nextName.equals(oldName)) enqueue(input.id(),"RENAME");
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void delete(String actor,List<Long> ids) {
        fence.lock();
        if (!com.knode.auth.UserTokenVerifier.validUserId(actor)) throw new MetadataFailure(515);
        if (ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(id -> id==null || id<=0)) throw new MetadataFailure(515);
        SortedSet<Long> ordered=new TreeSet<>(ids);
        for (long id:ordered) locked(id);
        for (long id:ordered) {
            db.update("UPDATE content_tag SET del_flag=1,updated_by=?,updated_at=? WHERE id=?",actor,Timestamp.from(Instant.now()),id);
            enqueue(id,"DELETE");
        }
    }
    private Map<String,Object> locked(long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT name,remark,del_flag FROM content_tag WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty() || ((Number)rows.get(0).get("DEL_FLAG")).intValue()!=0) throw new MetadataFailure(515);
        return rows.get(0);
    }
    private void enqueue(long tagId,String kind) {
        String job=UUID.randomUUID().toString();
        Timestamp now=Timestamp.from(Instant.now());
        db.update("INSERT INTO content_metadata_job(id,tag_id,kind,created_at) VALUES(?,?,?,?)",job,tagId,kind,now);
        db.update("INSERT INTO content_metadata_target(job_id,article_id,state,attempts,next_attempt) SELECT ?,a.id,'PENDING',0,CURRENT_TIMESTAMP(6) FROM content_article a JOIN content_article_tag x ON x.article_id=a.id WHERE x.tag_id=? AND a.status='0' AND a.deleted=0",job,tagId);
    }
    private static boolean blank(String s) { return s==null || s.isBlank(); }
}
