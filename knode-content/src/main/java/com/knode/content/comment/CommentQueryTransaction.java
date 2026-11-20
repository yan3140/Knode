package com.knode.content.comment;

import com.knode.content.metadata.ContentWriteFence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentQueryTransaction {
    private final JdbcTemplate db;
    private final ContentWriteFence fence;
    public CommentQueryTransaction(JdbcTemplate db,ContentWriteFence fence) { this.db=db; this.fence=fence; }
    public record Row(long id,Long article,long root,long target,String toUser,String actor,String content,Instant created) {}
    public record Snapshot(long total,List<Row> roots,List<Row> children,Set<String> users,boolean profileLookupLimited) {}

    @Transactional(isolation=Isolation.READ_COMMITTED,readOnly=false,timeout=5)
    public Snapshot read(String type,Long article,int page,int size) {
        fence.lock();
        if ("0".equals(type)) {
            Integer visible=db.queryForObject("SELECT COUNT(*) FROM content_article WHERE id=? AND status='0' AND deleted=0",Integer.class,article);
            if (visible==null || visible!=1) throw CommentFailure.missingArticle();
        }
        String scope=article==null?"comment_type=? AND article_id IS NULL":"comment_type=? AND article_id=?";
        List<Object> args=new ArrayList<>();args.add(type);if(article!=null)args.add(article);
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_comment WHERE "+scope+" AND deleted=0 AND root_id=-1",Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add(((long)page-1)*size);
        List<Row> roots=db.query("SELECT id,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at FROM content_comment WHERE "+
                scope+" AND deleted=0 AND root_id=-1 ORDER BY created_at,id LIMIT ? OFFSET ?",this::row,pageArgs.toArray());
        if(roots.isEmpty()) return new Snapshot(Objects.requireNonNull(total),List.of(),List.of(),Set.of(),false);
        String placeholders=String.join(",",Collections.nCopies(roots.size(),"?"));
        List<Object> childArgs=new ArrayList<>(args);roots.forEach(x -> childArgs.add(x.id()));childArgs.add(501);
        List<Row> children=db.query("SELECT id,article_id,root_id,to_comment_id,to_user_id,actor_id,content,created_at FROM content_comment WHERE "+
                scope+" AND deleted=0 AND root_id IN ("+placeholders+") ORDER BY root_id,created_at,id LIMIT ?",this::row,childArgs.toArray());
        if(children.size()>500) throw new CommentFailure(413,515);
        Set<String> ids=new LinkedHashSet<>();
        for(Row row:roots) addIds(ids,row);
        for(Row row:children) addIds(ids,row);
        boolean limited=ids.size()>100;
        Set<String> requested=new LinkedHashSet<>();
        for(String id:ids) { if(requested.size()==100) break; requested.add(id); }
        return new Snapshot(Objects.requireNonNull(total),List.copyOf(roots),List.copyOf(children),Set.copyOf(requested),limited);
    }
    private static void addIds(Set<String> ids,Row row) {
        ids.add(row.actor()); if(!"-1".equals(row.toUser())) ids.add(row.toUser());
    }
    private Row row(ResultSet r,int n) throws SQLException {
        long article=r.getLong(2); boolean absent=r.wasNull();
        return new Row(r.getLong(1),absent?null:article,r.getLong(3),r.getLong(4),r.getString(5),
                r.getString(6),r.getString(7),r.getTimestamp(8).toInstant());
    }
}
