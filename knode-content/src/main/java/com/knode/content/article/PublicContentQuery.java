package com.knode.content.article;

import com.knode.content.counter.CounterRead;
import com.knode.content.profile.UserProfileClient;
import com.knode.content.profile.UserProfileClient.Profile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PublicContentQuery {
    private final JdbcTemplate db;
    private final UserProfileClient users;
    private final CounterRead counters;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));
    public PublicContentQuery(JdbcTemplate db, UserProfileClient users, CounterRead counters) {
        this.db=db; this.users=users; this.counters=counters;
    }
    private record Row(long id,String title,String summary,long categoryId,String categoryName,String thumbnail,
                       String status,long count,String created,String author,String content,String comment) {}
    private Row row(ResultSet r,int n) throws SQLException {
        return new Row(r.getLong("id"),r.getString("title"),r.getString("summary"),r.getLong("category_id"),
            r.getString("category_name"),r.getString("thumbnail"),r.getString("status"),r.getLong("view_count"),
            DATE.format(r.getTimestamp("created_at").toInstant()),r.getString("author_id"),r.getString("content"),r.getString("is_comment"));
    }
    private static final String SELECT="SELECT a.id,a.title,a.summary,a.category_id,c.name AS category_name,a.thumbnail,a.status,a.view_count,a.created_at,a.author_id,NULL AS content,a.is_comment FROM content_article a LEFT JOIN content_category c ON c.id=a.category_id WHERE a.status='0' AND a.deleted=0";
    public Map<String,Object> list(String pageText,String sizeText,String categoryText) {
        int page=parseInt(pageText,1), size=parseInt(sizeText,10);
        if (page<1 || size<1 || size>100) throw new ArticleFailure(400,"Invalid pagination");
        long offset=((long)page-1)*size;
        if (offset>10000000L) throw new ArticleFailure(400,"Invalid pagination");
        long category=parseLong(categoryText,0);
        String filter=category>0?" AND a.category_id=?":"";
        List<Object> args=new ArrayList<>();
        if (category>0) args.add(category);
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_article a WHERE a.status='0' AND a.deleted=0"+filter,Long.class,args.toArray());
        args.add(size); args.add(offset);
        List<Row> rows=db.query(SELECT+filter+" ORDER BY a.is_top DESC,a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",this::row,args.toArray());
        Map<Long,Long> live=counters.latest(rows.stream().map(Row::id).toList());
        Map<String,Profile> authors=users.lookup(rows.stream().map(Row::author).toList());
        List<Map<String,Object>> result=new ArrayList<>();
        for (Row r:rows) {
            Map<String,Object> m=base(r);
            m.put("categoryId",Long.toString(r.categoryId())); m.put("categoryName",r.categoryName());
            m.put("thumbnail",r.thumbnail()); m.put("status",r.status());
            m.put("viewCount",Long.toString(Math.max(r.count(),live.getOrDefault(r.id(),r.count())))); m.put("createTime",r.created());
            addAuthor(m,r,authors); result.add(m);
        }
        return Map.of("rows",result,"total",Long.toString(Objects.requireNonNull(total)));
    }
    public Map<String,Object> detail(long id) {
        List<Row> rows=db.query(SELECT.replace("NULL AS content","a.content")+" AND a.id=?",this::row,id);
        if (rows.isEmpty()) throw new ArticleFailure(404,"Article not found");
        Row r=rows.get(0);
        Map<Long,Long> live=counters.latest(List.of(r.id()));
        Map<String,Object> m=base(r);
        m.put("categoryId",Long.toString(r.categoryId())); m.put("categoryName",r.categoryName());
        m.put("isComment",r.comment()); m.put("content",r.content());
        m.put("viewCount",Long.toString(Math.max(r.count(),live.getOrDefault(r.id(),r.count())))); m.put("createTime",r.created());
        addAuthor(m,r,users.lookup(List.of(r.author())));
        return m;
    }
    private record Hot(long id,String title,long count) {}
    public List<Map<String,Object>> hot() {
        List<Hot> rows=db.query("SELECT id,title,view_count FROM content_article WHERE status='0' AND deleted=0 ORDER BY view_count DESC,id DESC LIMIT 10",
            (r,n) -> new Hot(r.getLong(1),r.getString(2),r.getLong(3)));
        Map<Long,Long> live=counters.latest(rows.stream().map(Hot::id).toList());
        return rows.stream().map(r -> Map.<String,Object>of("id",Long.toString(r.id()),"title",r.title(),
                "viewCount",Long.toString(Math.max(r.count(),live.getOrDefault(r.id(),r.count()))))).toList();
    }
    public List<Map<String,Object>> categories() {
        return db.query("SELECT c.id,c.name,c.description,c.status FROM content_category c WHERE c.status='0' AND EXISTS (SELECT 1 FROM content_article a WHERE a.category_id=c.id AND a.status='0' AND a.deleted=0) ORDER BY c.id",
            (r,n) -> { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",Long.toString(r.getLong(1))); m.put("name",r.getString(2)); m.put("description",r.getString(3)); m.put("status",r.getString(4)); return m; });
    }
    public List<Map<String,Object>> tags() {
        return db.query("SELECT id,name,remark FROM content_tag WHERE del_flag=0 ORDER BY id",
            (r,n) -> { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",Long.toString(r.getLong(1))); m.put("name",r.getString(2)); m.put("remark",r.getString(3)); return m; });
    }
    private static Map<String,Object> base(Row r) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",Long.toString(r.id())); m.put("title",r.title()); m.put("summary",r.summary()); return m;
    }
    private static void addAuthor(Map<String,Object> m,Row r,Map<String,Profile> users) {
        Profile p=users.getOrDefault(r.author(),new Profile("UNAVAILABLE",null,null));
        m.put("authorId",r.author()); m.put("authorName",p.nickName()); m.put("authorAvatar",p.avatar());
        m.put("authorInfoStatus",p.status());
    }
    private static int parseInt(String s,int fallback) {
        if (s==null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { throw new ArticleFailure(400,"Invalid pagination"); }
    }
    private static long parseLong(String s,long fallback) {
        if (s==null) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw new ArticleFailure(400,"Invalid id"); }
    }
}
