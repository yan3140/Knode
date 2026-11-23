package com.knode.content.counter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.metadata.ContentWriteFence;
import com.knode.content.relay.DeliveryRegistration;
import com.knode.content.relay.FactTrace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CounterFlushTransaction {
    private final JdbcTemplate db;
    private final ContentWriteFence fence;
    private final ObjectMapper json;
    private final DeliveryRegistration delivery;
    private final FactTrace trace;
    public CounterFlushTransaction(JdbcTemplate db,ContentWriteFence fence,ObjectMapper json,DeliveryRegistration delivery,FactTrace trace) {
        this.db=db; this.fence=fence; this.json=json; this.delivery=delivery; this.trace=trace;
    }
    private record Row(long count,long revision,String status,boolean deleted) {}
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public boolean persist(CounterStore.Snapshot snapshot) {
        if(snapshot==null || snapshot.articleId()<=0 || snapshot.value()<0) throw new CounterUnavailable();
        fence.lock();
        List<Row> rows=db.query("SELECT view_count,counter_revision,status,deleted FROM content_article WHERE id=? FOR UPDATE",
                (r,n) -> new Row(r.getLong(1),r.getLong(2),r.getString(3),r.getBoolean(4)),snapshot.articleId());
        if(rows.isEmpty()) throw new CounterUnavailable();
        Row old=rows.get(0);
        if(old.count()<0 || old.revision()<0) throw new CounterUnavailable();
        if(snapshot.value()<=old.count()) return false;
        long revision=Math.addExact(old.revision(),1);
        db.update("UPDATE content_article SET view_count=?,counter_revision=? WHERE id=?",snapshot.value(),revision,snapshot.articleId());
        String visibility=old.deleted()?"DELETED":("0".equals(old.status())?"PUBLIC":"WITHDRAWN");
        String eventId=UUID.randomUUID().toString(); Instant now=Instant.now();
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("articleId",Long.toString(snapshot.articleId())); payload.put("viewCount",Long.toString(snapshot.value()));
        payload.put("counterRevision",Long.toString(revision));
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("eventId",eventId); event.put("eventType","ArticleCounterChanged"); event.put("schemaVersion",1);
        event.put("producer","knode-content"); event.put("aggregateType","ARTICLE_COUNTER");
        event.put("aggregateId",Long.toString(snapshot.articleId())); event.put("sourceRevision",Long.toString(revision));
        event.put("visibility",visibility); event.put("occurredAt",now.toString()); trace.capture(event); event.put("payload",payload);
        try {
            db.update("INSERT INTO content_article_counter_event(event_id,article_id,counter_revision,payload,created_at) VALUES(?,?,?,?,?)",
                    eventId,snapshot.articleId(),revision,json.writeValueAsString(event),Timestamp.from(now));
            delivery.register(eventId,"COUNTER","ArticleCounterChanged");
        } catch(JsonProcessingException failure) { throw new CounterUnavailable(); }
        return true;
    }
}
