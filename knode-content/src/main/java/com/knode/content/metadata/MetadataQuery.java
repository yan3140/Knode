package com.knode.content.metadata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MetadataQuery {
    private final JdbcTemplate db;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));
    public MetadataQuery(JdbcTemplate db) { this.db=db; }

    public Map<String,Object> tags(String page,String size,String name,String remark) {
        long[] p=page(page,size);
        List<String> filters=new ArrayList<>(); List<Object> args=new ArrayList<>();
        like(filters,args,"name",name,256); like(filters,args,"remark",remark,1024);
        String where=" WHERE del_flag=0"+(filters.isEmpty()?"":" AND ("+String.join(" OR ",filters)+")");
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_tag"+where,Long.class,args.toArray());
        args.add(p[0]); args.add(p[1]);
        List<Map<String,Object>> rows=db.query("SELECT id,name,remark FROM content_tag"+where+" ORDER BY id LIMIT ? OFFSET ?",this::tagRow,args.toArray());
        return Map.of("rows",rows,"total",Long.toString(Objects.requireNonNull(total)));
    }
    public List<Map<String,Object>> allTags() {
        return db.query("SELECT id,name,remark FROM content_tag WHERE del_flag=0 ORDER BY id",this::tagRow);
    }
    public Map<String,Object> tag(long id) {
        List<Map<String,Object>> rows=db.query("SELECT id,name,remark,created_by,created_at,updated_by,updated_at FROM content_tag WHERE id=? AND del_flag=0",
            (r,n) -> { Map<String,Object> m=tagRow(r,n); audit(m,r); return m; },id);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> links(String page,String size,String name,String status) {
        long[] p=page(page,size); List<String> filters=new ArrayList<>(); List<Object> args=new ArrayList<>();
        like(filters,args,"name",name,256);
        if(status!=null && !status.isBlank()) {
            if(!Set.of("0","1","2").contains(status)) throw new MetadataFailure(515);
            filters.add("status=?"); args.add(status);
        }
        String where=" WHERE del_flag=0"+(filters.isEmpty()?"":" AND "+String.join(" AND ",filters));
        Long total=db.queryForObject("SELECT COUNT(*) FROM content_link"+where,Long.class,args.toArray());
        args.add(p[0]); args.add(p[1]);
        List<Map<String,Object>> rows=db.query("SELECT id,name,logo,description,address,status,created_by,created_at,updated_by,updated_at FROM content_link"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::adminLink,args.toArray());
        return Map.of("rows",rows,"total",Long.toString(Objects.requireNonNull(total)));
    }
    public Map<String,Object> link(long id) {
        List<Map<String,Object>> rows=db.query("SELECT id,name,logo,description,address,status,created_by,created_at,updated_by,updated_at FROM content_link WHERE id=? AND del_flag=0",this::adminLink,id);
        if(rows.isEmpty()) throw new MetadataFailure(515);
        return rows.get(0);
    }
    public List<Map<String,Object>> publicLinks() {
        return db.query("SELECT id,name,logo,description,address FROM content_link WHERE status='0' AND del_flag=0 ORDER BY id",this::publicLink);
    }
    private Map<String,Object> tagRow(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",Long.toString(r.getLong("id")));
        m.put("name",r.getString("name")); m.put("remark",r.getString("remark")); return m;
    }
    private Map<String,Object> publicLink(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",Long.toString(r.getLong("id")));
        m.put("name",r.getString("name")); m.put("logo",r.getString("logo"));
        m.put("description",r.getString("description")); m.put("address",r.getString("address")); return m;
    }
    private Map<String,Object> adminLink(ResultSet r,int n) throws SQLException {
        Map<String,Object> m=publicLink(r,n); m.put("status",r.getString("status")); audit(m,r); return m;
    }
    private static void audit(Map<String,Object> m,ResultSet r) throws SQLException {
        m.put("createBy",r.getString("created_by")); m.put("updateBy",r.getString("updated_by"));
        Timestamp created=r.getTimestamp("created_at"), updated=r.getTimestamp("updated_at");
        m.put("createTime",created==null?null:DATE.format(created.toInstant())); m.put("updateTime",updated==null?null:DATE.format(updated.toInstant()));
    }
    private static void like(List<String> filters,List<Object> args,String column,String value,int max) {
        if(value==null || value.isBlank()) return;
        if(value.length()>max) throw new MetadataFailure(515);
        filters.add(column+" LIKE ? ESCAPE '!'");
        args.add("%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
    }
    public static long id(String text) {
        try {
            if(text==null || !text.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id=Long.parseLong(text); if(id<=0) throw new NumberFormatException(); return id;
        } catch(NumberFormatException failure) { throw new MetadataFailure(515); }
    }
    public static List<Long> ids(String text) {
        String[] parts=text.split(",",-1);
        if(parts.length==0 || parts.length>100) throw new MetadataFailure(515);
        List<Long> ids=new ArrayList<>(); for(String s:parts) ids.add(id(s)); return ids;
    }
    private static long[] page(String p,String s) {
        try {
            int page=p==null?1:Integer.parseInt(p),size=s==null?10:Integer.parseInt(s);
            long offset=((long)page-1)*size;
            if(page<1 || size<1 || size>100 || offset>10000000L) throw new NumberFormatException();
            return new long[]{size,offset};
        } catch(NumberFormatException failure) { throw new MetadataFailure(515); }
    }
}
