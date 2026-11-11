package com.knode.content.metadata;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {
    private final JdbcTemplate db;
    public LinkService(JdbcTemplate db) { this.db=db; }
    @Transactional public void create(String actor,LinkInput in) {
        actor(actor);
        if(in==null) throw new MetadataFailure(515);
        String status=blank(in.status())?"2":in.status();
        String logo=blank(in.logo())?null:in.logo();
        validate(in.name(),logo,in.description(),in.address(),status);
        Timestamp now=Timestamp.from(Instant.now());
        db.update("INSERT INTO content_link(name,logo,description,address,status,del_flag,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,0,?,?,?,?)",
            in.name(),logo,in.description(),in.address(),status,actor,now,actor,now);
    }
    @Transactional public void update(String actor,LinkInput in) {
        actor(actor);
        if(in==null || in.id()==null || in.id()<=0) throw new MetadataFailure(515);
        Map<String,Object> old=locked(in.id());
        String name=in.name()==null?(String)old.get("name"):in.name();
        String logo=in.logo()==null?(String)old.get("logo"):(blank(in.logo())?null:in.logo());
        String description=in.description()==null?(String)old.get("description"):in.description();
        String address=in.address()==null?(String)old.get("address"):in.address();
        String status=in.status()==null?(String)old.get("status"):in.status();
        validate(name,logo,description,address,status);
        db.update("UPDATE content_link SET name=?,logo=?,description=?,address=?,status=?,updated_by=?,updated_at=? WHERE id=?",
            name,logo,description,address,status,actor,Timestamp.from(Instant.now()),in.id());
    }
    @Transactional public void status(String actor,Long id,String status) {
        actor(actor);
        if(id==null || id<=0 || !validStatus(status)) throw new MetadataFailure(515);
        locked(id);
        db.update("UPDATE content_link SET status=?,updated_by=?,updated_at=? WHERE id=?",status,actor,Timestamp.from(Instant.now()),id);
    }
    @Transactional public void delete(String actor,List<Long> ids) {
        actor(actor);
        if(ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(id -> id==null || id<=0)) throw new MetadataFailure(515);
        SortedSet<Long> ordered=new TreeSet<>(ids);
        for(long id:ordered) locked(id);
        for(long id:ordered) db.update("UPDATE content_link SET del_flag=1,updated_by=?,updated_at=? WHERE id=?",actor,Timestamp.from(Instant.now()),id);
    }
    private Map<String,Object> locked(long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT name,logo,description,address,status,del_flag FROM content_link WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty() || ((Number)rows.get(0).get("del_flag")).intValue()!=0) throw new MetadataFailure(515);
        return rows.get(0);
    }
    private static void validate(String name,String logo,String description,String address,String status) {
        if(blank(name) || name.length()>256 || description!=null && description.length()>1024
                || !url(address) || logo!=null && !url(logo) || !validStatus(status)) throw new MetadataFailure(515);
    }
    private static boolean url(String value) {
        if(blank(value) || value.length()>2048 || value.chars().anyMatch(c -> c<=31 || c==127)) return false;
        try {
            URI uri=URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost()!=null && uri.getRawUserInfo()==null;
        } catch(IllegalArgumentException failure) { return false; }
    }
    private static boolean validStatus(String s) { return "0".equals(s) || "1".equals(s) || "2".equals(s); }
    private static boolean blank(String s) { return s==null || s.isBlank(); }
    private static void actor(String s) { if(!com.knode.auth.UserTokenVerifier.validUserId(s)) throw new MetadataFailure(515); }
}
