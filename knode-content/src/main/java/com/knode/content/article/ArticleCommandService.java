package com.knode.content.article;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleCommandService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ArticleCommandService(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    private record Row(long id, String title, String content, String summary, String thumbnail,
                       long category, String comment, String status, String author, Instant created,
                       long revision, String fact, Instant published, boolean deleted, long viewCount, long counterRevision) {}

    @Transactional
    public long create(String actor, ArticleCommand input, String correlationId) {
        validate(actor, input);
        validateReferences(input);
        Instant now = Instant.now();
        String fact = "0".equals(input.status()) ? UUID.randomUUID().toString() : null;
        KeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement p = connection.prepareStatement("INSERT INTO content_article(title,content,summary,thumbnail,category_id,is_top,is_comment,status,author_id,created_at,updated_at,updated_by,view_count,counter_revision,source_revision,publication_fact_id,published_at,deleted) VALUES(?,?,?,?,?,'0',?,?,?,?,?,?,0,0,1,?,?,0)", Statement.RETURN_GENERATED_KEYS);
            int i=1; p.setString(i++,input.title()); p.setString(i++,input.content());
            p.setString(i++,input.summary()); p.setString(i++,input.thumbnail()); p.setLong(i++,input.categoryId());
            p.setString(i++,input.isComment()); p.setString(i++,input.status()); p.setString(i++,actor);
            p.setTimestamp(i++,Timestamp.from(now)); p.setTimestamp(i++,Timestamp.from(now)); p.setString(i++,actor);
            p.setString(i++,fact); p.setTimestamp(i, fact == null ? null : Timestamp.from(now)); return p;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        replaceTags(id, input.tags());
        if (fact != null) emit(load(id, false), "ArticlePublished", "PUBLIC", correlationId);
        return id;
    }

    @Transactional
    public void edit(String actor, long id, ArticleCommand input, String correlationId) {
        validate(actor, input);
        Row old = owned(actor, id);
        validateReferences(input);
        Instant now = Instant.now();
        boolean first = old.fact() == null && "0".equals(input.status());
        String fact = first ? UUID.randomUUID().toString() : old.fact();
        Instant published = first ? now : old.published();
        long revision = Math.addExact(old.revision(), 1);
        db.update("UPDATE content_article SET title=?,content=?,summary=?,thumbnail=?,category_id=?,is_comment=?,status=?,updated_at=?,updated_by=?,source_revision=?,publication_fact_id=?,published_at=? WHERE id=?",
            input.title(), input.content(), input.summary(), input.thumbnail(), input.categoryId(), input.isComment(), input.status(),
            Timestamp.from(now), actor, revision, fact, published == null ? null : Timestamp.from(published), id);
        replaceTags(id, input.tags());
        Row current = load(id, false);
        if (first) emit(current, "ArticlePublished", "PUBLIC", correlationId);
        else if ("0".equals(old.status()) && "1".equals(input.status())) emit(current, "ArticleVisibilityChanged", "WITHDRAWN", correlationId);
        else if ("1".equals(old.status()) && "0".equals(input.status())) emit(current, "ArticleVisibilityChanged", "PUBLIC", correlationId);
        else if ("0".equals(input.status())) emit(current, "ArticleContentChanged", "PUBLIC", correlationId);
    }

    @Transactional
    public void setStatus(String actor, long id, String status, String correlationId) {
        if (!validActor(actor) || !binary(status)) throw new ArticleFailure(400,"Invalid article parameters");
        Row old = owned(actor, id);
        if (old.status().equals(status)) return;
        if ("0".equals(status)) {
            List<Long> tags = db.queryForList("SELECT tag_id FROM content_article_tag WHERE article_id=?", Long.class, id);
            validateReferences(old.category(), tags);
        }
        Instant now = Instant.now();
        boolean first = old.fact() == null && "0".equals(status);
        String fact = first ? UUID.randomUUID().toString() : old.fact();
        Instant published = first ? now : old.published();
        db.update("UPDATE content_article SET status=?,updated_at=?,updated_by=?,source_revision=?,publication_fact_id=?,published_at=? WHERE id=?",
            status, Timestamp.from(now), actor, Math.addExact(old.revision(), 1), fact,
            published == null ? null : Timestamp.from(published), id);
        Row current = load(id, false);
        if (first) emit(current, "ArticlePublished", "PUBLIC", correlationId);
        else if (fact != null) emit(current, "ArticleVisibilityChanged", "0".equals(status) ? "PUBLIC" : "WITHDRAWN", correlationId);
    }

    @Transactional
    public void delete(String actor, long id, String correlationId) {
        if (!validActor(actor)) throw new ArticleFailure(400,"Invalid actor");
        Row old = owned(actor,id);
        db.update("UPDATE content_article SET deleted=1,updated_at=?,updated_by=?,source_revision=? WHERE id=?",
            Timestamp.from(Instant.now()), actor, Math.addExact(old.revision(),1), id);
        if (old.fact() != null) emit(load(id, false), "ArticleDeleted", "DELETED", correlationId);
    }

    private Row owned(String actor, long id) {
        Row row = load(id, true);
        if (row == null || row.deleted()) throw new ArticleFailure(404,"Article not found");
        if (!row.author().equals(actor)) throw new ArticleFailure(403,"Forbidden");
        return row;
    }

    private Row load(long id, boolean lock) {
        List<Row> rows = db.query("SELECT * FROM content_article WHERE id=?" + (lock ? " FOR UPDATE" : ""), (r,n) ->
            new Row(r.getLong("id"), r.getString("title"), r.getString("content"), r.getString("summary"), r.getString("thumbnail"),
                r.getLong("category_id"), r.getString("is_comment"), r.getString("status"), r.getString("author_id"),
                r.getTimestamp("created_at").toInstant(), r.getLong("source_revision"), r.getString("publication_fact_id"),
                r.getTimestamp("published_at") == null ? null : r.getTimestamp("published_at").toInstant(), r.getBoolean("deleted"),
                r.getLong("view_count"), r.getLong("counter_revision")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validate(String actor, ArticleCommand a) {
        if (!validActor(actor) || a == null || blank(a.title()) || a.title().length() > 256 ||
            blank(a.content()) || a.content().length() > 1000000 || (a.summary()!=null && a.summary().length()>1024) ||
            (a.thumbnail()!=null && a.thumbnail().length()>2048) || a.categoryId()==null || a.categoryId()<=0 ||
            !binary(a.isComment()) || !binary(a.status()) || a.tags()==null || a.tags().size()>50 ||
            a.tags().stream().anyMatch(x -> x==null || x<=0) || new HashSet<>(a.tags()).size()!=a.tags().size())
            throw new ArticleFailure(400,"Invalid article parameters");
    }
    private void validateReferences(ArticleCommand a) { validateReferences(a.categoryId(), a.tags()); }
    private void validateReferences(long categoryId, List<Long> tags) {
        Integer categories = db.queryForObject("SELECT COUNT(*) FROM content_category WHERE id=? AND status='0'", Integer.class,categoryId);
        if (categories == null || categories != 1) throw new ArticleFailure(400,"Invalid category");
        for (Long id : tags) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM content_tag WHERE id=? AND del_flag=0", Integer.class,id);
            if (count == null || count != 1) throw new ArticleFailure(400,"Invalid tag");
        }
    }
    private void replaceTags(long id, List<Long> tags) {
        db.update("DELETE FROM content_article_tag WHERE article_id=?",id);
        for (Long tag : tags) db.update("INSERT INTO content_article_tag(article_id,tag_id) VALUES(?,?)",id,tag);
    }
    private static boolean blank(String s) { return s==null || s.isBlank(); }
    private static boolean binary(String s) { return "0".equals(s) || "1".equals(s); }
    private static boolean validActor(String s) { return com.knode.auth.UserTokenVerifier.validUserId(s); }

    private void emit(Row row, String type, String visibility, String correlationId) {
        String eventId = UUID.randomUUID().toString();
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("articleId",Long.toString(row.id()));
        if ("PUBLIC".equals(visibility)) {
            String category = db.queryForObject("SELECT name FROM content_category WHERE id=?",String.class,row.category());
            List<Map<String,Object>> tags = db.query("SELECT t.id,t.name FROM content_tag t JOIN content_article_tag at ON at.tag_id=t.id WHERE at.article_id=? ORDER BY t.id",
                (rs,n) -> Map.of("id",Long.toString(rs.getLong("id")),"name",rs.getString("name")),row.id());
            payload.put("authorId",row.author()); payload.put("title",row.title()); payload.put("summary",row.summary());
            payload.put("content",row.content()); payload.put("thumbnail",row.thumbnail()); payload.put("isComment",row.comment());
            payload.put("category",Map.of("id",Long.toString(row.category()),"name",category)); payload.put("tags",tags);
            payload.put("createTime",row.created().toString()); payload.put("publishedAt",row.published().toString());
            payload.put("viewCount",Long.toString(row.viewCount())); payload.put("counterRevision",Long.toString(row.counterRevision()));
        }
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("eventId",eventId); event.put("eventType",type); event.put("schemaVersion",1);
        event.put("producer","knode-content"); event.put("aggregateType","ARTICLE");
        event.put("aggregateId",Long.toString(row.id())); event.put("sourceRevision",Long.toString(row.revision()));
        event.put("publicationFactId",row.fact()); event.put("occurredAt",Instant.now().toString()); event.put("visibility",visibility);
        if (correlationId != null && correlationId.matches("[A-Za-z0-9._-]{1,64}")) event.put("correlationId",correlationId);
        event.put("payload",payload);
        try {
            String serialized=json.writeValueAsString(event);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8000000)
                throw new ArticleFailure(400,"Article event exceeds size limit");
            db.update("INSERT INTO content_article_event(event_id,article_id,source_revision,event_type,payload,created_at) VALUES(?,?,?,?,?,?)",
                eventId,row.id(),row.revision(),type,serialized,Timestamp.from(Instant.now()));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Event serialization failed",e); }
    }
}
