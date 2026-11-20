package com.knode.content.comment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.auth.UserTokenVerifier;
import com.knode.content.metadata.ContentWriteFence;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentCommandService {
    private final JdbcTemplate db;
    private final ContentWriteFence fence;
    private final ObjectMapper json;
    public CommentCommandService(JdbcTemplate db,ContentWriteFence fence,ObjectMapper json) {
        this.db=db; this.fence=fence; this.json=json;
    }
    private record Article(String author,String status,boolean deleted,String allowComment) {}
    private record Comment(long id,String type,Long article,long root,String author,boolean deleted) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long create(String actor,CommentRules.Valid input,String correlationId) {
        fence.lock();
        if (!UserTokenVerifier.validUserId(actor) || input==null) throw CommentFailure.invalid();
        String receiver=null;
        if ("0".equals(input.type())) {
            List<Article> rows=db.query("SELECT author_id,status,deleted,is_comment FROM content_article WHERE id=? FOR UPDATE",
                (r,n) -> new Article(r.getString(1),r.getString(2),r.getBoolean(3),r.getString(4)),input.articleId());
            if (rows.isEmpty() || rows.get(0).deleted() || !"0".equals(rows.get(0).status())) throw CommentFailure.missingArticle();
            if (!"1".equals(rows.get(0).allowComment())) throw new CommentFailure(403,403);
            receiver=rows.get(0).author();
        }
        String targetUser="-1";
        if (input.rootId()!=-1) {
            Map<Long,Comment> locked=new HashMap<>();
            for (long id:new TreeSet<>(List.of(input.rootId(),input.targetId()))) {
                List<Comment> rows=db.query("SELECT id,comment_type,article_id,root_id,actor_id,deleted FROM content_comment WHERE id=? FOR UPDATE",
                    (r,n) -> {
                        long article=r.getLong(3); boolean noArticle=r.wasNull();
                        return new Comment(r.getLong(1),r.getString(2),noArticle?null:article,r.getLong(4),r.getString(5),r.getBoolean(6));
                    },id);
                if (rows.isEmpty()) throw CommentFailure.invalid();
                locked.put(id,rows.get(0));
            }
            Comment root=locked.get(input.rootId()),target=locked.get(input.targetId());
            if (!inThread(root,input) || root.root()!=-1 || !inThread(target,input) ||
                    (target.id()!=root.id() && target.root()!=root.id())) throw CommentFailure.invalid();
            if (input.suppliedTargetUser()!=null && !target.author().equals(input.suppliedTargetUser().toString())) throw CommentFailure.invalid();
            targetUser=target.author();
            if ("0".equals(input.type())) receiver=targetUser;
        }
        Timestamp now=Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class));
        var key=new GeneratedKeyHolder();
        String toUser=targetUser;
        db.update(connection -> {
            PreparedStatement s=connection.prepareStatement("INSERT INTO content_comment(comment_type,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at,deleted) VALUES(?,?,?,?,?,?,?,?,0)",Statement.RETURN_GENERATED_KEYS);
            s.setString(1,input.type()); if(input.articleId()==null)s.setNull(2,java.sql.Types.BIGINT); else s.setLong(2,input.articleId());
            s.setLong(3,input.rootId()); s.setLong(4,input.targetId()); s.setString(5,toUser); s.setString(6,actor);
            s.setString(7,input.content()); s.setTimestamp(8,now); return s;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        String eventType=("0".equals(input.type())?"Comment":"LinkComment")+(input.rootId()==-1?"Created":"Replied");
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("commentId",Long.toString(id)); payload.put("articleId",input.articleId()==null?null:input.articleId().toString());
        payload.put("rootCommentId",Long.toString(input.rootId())); payload.put("toCommentId",Long.toString(input.targetId()));
        payload.put("actorId",actor); payload.put("receiverId",receiver); payload.put("commentType",input.type());
        payload.put("contentPreview",CommentRules.preview(input.content()));
        String eventId=UUID.randomUUID().toString();
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("eventId",eventId); event.put("eventType",eventType); event.put("schemaVersion",1);
        event.put("aggregateType","COMMENT"); event.put("aggregateId",Long.toString(id)); event.put("sourceRevision","1");
        event.put("producer","knode-content"); event.put("occurredAt",now.toInstant().toString());
        if (correlationId!=null && correlationId.matches("[A-Za-z0-9._-]{1,64}")) event.put("correlationId",correlationId);
        event.put("payload",payload);
        try {
            db.update("INSERT INTO content_comment_event(event_id,comment_id,source_revision,event_type,payload,created_at) VALUES(?,?,1,?,?,?)",
                eventId,id,eventType,json.writeValueAsString(event),now);
        } catch(JsonProcessingException failure) { throw new IllegalStateException("Comment event serialization failed"); }
        return id;
    }
    private static boolean inThread(Comment row,CommentRules.Valid input) {
        return row!=null && !row.deleted() && row.type().equals(input.type()) && Objects.equals(row.article(),input.articleId());
    }
}
