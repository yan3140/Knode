package com.knode.content.admin;

import com.knode.content.article.ArticleFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminContentQuery {
    private final JdbcTemplate db;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));
    public AdminContentQuery(JdbcTemplate db) { this.db=db; }
    public Map<String,Object> articles(String page,String size,String title,String summary,String category,String status) {
        long[] limit=page(page,size);
        if (title!=null && title.length()>256 || summary!=null && summary.length()>1024)
            throw new ArticleFailure(400,"Invalid parameters");
        StringBuilder where=new StringBuilder(" WHERE a.deleted=0");
        List<Object> args=new ArrayList<>();
        like(where,args,"a.title",title); like(where,args,"a.summary",summary);
        if (category!=null && !category.isBlank()) { where.append(" AND a.category_id=?"); args.add(categoryFilter(category)); }
        if (status!=null && !status.isBlank()) {
            if (!"0".equals(status) && !"1".equals(status)) throw new ArticleFailure(400,"Invalid parameters");
            where.append(" AND a.status=?"); args.add(status);
        }
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_article a"+where,Long.class,args.toArray());
        args.add(limit[0]); args.add(limit[1]);
        List<Map<String,Object>> rows=db.query("SELECT a.id,a.title,a.summary,a.thumbnail,a.category_id,c.name AS category_name,a.is_top,a.is_comment,a.status,a.author_id,a.created_at,a.updated_at,a.updated_by,a.view_count FROM content_article a LEFT JOIN content_category c ON c.id=a.category_id"+where+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",this::articleRow,args.toArray());
        return Map.of("rows",rows,"total",Long.toString(Objects.requireNonNull(total)));
    }
    public Map<String,Object> article(long id) {
        List<Map<String,Object>> rows=db.query("SELECT id,title,thumbnail,is_top,is_comment,content,category_id,summary,status FROM content_article WHERE id=? AND deleted=0",(r,n) -> {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",Long.toString(r.getLong("id"))); m.put("title",r.getString("title")); m.put("thumbnail",r.getString("thumbnail"));
            m.put("isTop",r.getString("is_top")); m.put("isComment",r.getString("is_comment")); m.put("content",r.getString("content"));
            m.put("categoryId",Long.toString(r.getLong("category_id"))); m.put("summary",r.getString("summary")); m.put("status",r.getString("status"));
            return m;
        },id);
        if (rows.isEmpty()) throw new ArticleFailure(404,"Article not found");
        rows.get(0).put("tags",db.queryForList("SELECT tag_id FROM content_article_tag WHERE article_id=? ORDER BY tag_id",Long.class,id).stream().map(String::valueOf).toList());
        return rows.get(0);
    }
    public List<Map<String,Object>> allCategories() {
        return db.query("SELECT id,name,description,status FROM content_category WHERE status='0' ORDER BY id",this::categoryRow);
    }
    public Map<String,Object> categories(String page,String size) {
        long[] limit=page(page,size);
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_category",Long.class);
        List<Map<String,Object>> rows=db.query("SELECT id,name,description,status FROM content_category ORDER BY id LIMIT ? OFFSET ?",this::categoryRow,limit[0],limit[1]);
        return Map.of("rows",rows,"total",Long.toString(Objects.requireNonNull(total)));
    }
    private Map<String,Object> articleRow(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",Long.toString(r.getLong("id"))); m.put("title",r.getString("title")); m.put("summary",r.getString("summary"));
        m.put("thumbnail",r.getString("thumbnail")); m.put("categoryId",Long.toString(r.getLong("category_id"))); m.put("categoryName",r.getString("category_name"));
        m.put("isTop",r.getString("is_top")); m.put("isComment",r.getString("is_comment")); m.put("status",r.getString("status"));
        m.put("createBy",r.getString("author_id")); m.put("authorId",r.getString("author_id")); m.put("viewCount",Long.toString(r.getLong("view_count")));
        m.put("createTime",DATE.format(r.getTimestamp("created_at").toInstant())); m.put("updateBy",r.getString("updated_by"));
        m.put("updateTime",DATE.format(r.getTimestamp("updated_at").toInstant())); return m;
    }
    private Map<String,Object> categoryRow(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",Long.toString(r.getLong("id")));
        m.put("name",r.getString("name")); m.put("description",r.getString("description")); m.put("status",r.getString("status")); return m;
    }
    private static void like(StringBuilder where,List<Object> args,String column,String value) {
        if (value==null || value.isBlank()) return;
        where.append(" AND ").append(column).append(" LIKE ? ESCAPE '!'");
        args.add("%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
    }
    public static long id(String text) {
        try {
            if (text==null || !text.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id=Long.parseLong(text); if (id<=0) throw new NumberFormatException(); return id;
        } catch (NumberFormatException e) { throw new ArticleFailure(400,"Invalid parameters"); }
    }
    private static long categoryFilter(String text) {
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new ArticleFailure(400,"Invalid parameters"); }
    }
    private static long[] page(String p,String s) {
        try {
            int page=p==null?1:Integer.parseInt(p), size=s==null?10:Integer.parseInt(s);
            long offset=((long)page-1)*size;
            if(page<1 || size<1 || size>100 || offset>10000000L) throw new NumberFormatException();
            return new long[]{size,offset};
        } catch(NumberFormatException e) { throw new ArticleFailure(400,"Invalid pagination"); }
    }
}
