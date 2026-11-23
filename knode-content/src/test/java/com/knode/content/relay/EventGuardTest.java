package com.knode.content.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventGuardTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String ID="550e8400-e29b-41d4-a716-446655440000";
    final EventGuard guard=new EventGuard(mock(FactTrace.class));
    RelayTransaction.Claim claim(String type) { return new RelayTransaction.Claim(ID,"ARTICLE",type,"lease",1,false); }
    ObjectNode event(String type,String visibility,int version) {
        ObjectNode e=JSON.createObjectNode();e.put("eventId",ID);e.put("eventType",type);e.put("schemaVersion",version);
        e.put("producer","knode-content");e.put("aggregateType","ARTICLE");e.put("aggregateId","42");e.put("sourceRevision","1");
        e.put("publicationFactId","article-42-first");e.put("occurredAt","2026-11-20T00:00:00Z");e.put("visibility",visibility);
        ObjectNode p=e.putObject("payload");p.put("articleId","42");
        if("PUBLIC".equals(visibility)) {
            p.put("authorId","7");p.put("title","title");p.putNull("summary");p.put("content","public text");
            p.putNull("thumbnail");if(version==2)p.put("isTop","0");p.put("isComment","1");
            p.putObject("category").put("id","2").put("name","Java");p.putArray("tags");
            p.put("createTime","2026-11-19T00:00:00Z");p.put("publishedAt","2026-11-20T00:00:00Z");
            p.put("viewCount","0");p.put("counterRevision","0");
        }
        return e;
    }
    @Test void v1AndV2LegalShapesPassAndPrivateObjectsFail() throws Exception {
        for(int version:new int[]{1,2}) {
            ObjectNode valid=event("ArticlePublished","PUBLIC",version);
            assertDoesNotThrow(() -> guard.validate(claim("ArticlePublished"),valid.toString()));
            for(String field:new String[]{"summary","thumbnail"}) {
                ObjectNode bad=valid.deepCopy();bad.with("payload").putObject(field).put("private","secret");
                assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticlePublished"),bad.toString()));
            }
            ObjectNode badIdentity=valid.deepCopy();badIdentity.putObject("publicationFactId").put("private","secret");
            assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticlePublished"),badIdentity.toString()));
            ObjectNode nested=valid.deepCopy();nested.with("payload").with("category").putObject("extra").put("private","secret");
            assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticlePublished"),nested.toString()));
            ObjectNode trace=valid.deepCopy();trace.putObject("traceContext").put("traceparent","invalid").put("tracestate","private");
            assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticlePublished"),trace.toString()));
        }
    }
    @Test void tombstoneRejectsBodyAndObjectReason() {
        ObjectNode valid=event("ArticleVisibilityChanged","WITHDRAWN",1);
        valid.with("payload").put("reason","UNPUBLISHED");
        assertDoesNotThrow(() -> guard.validate(claim("ArticleVisibilityChanged"),valid.toString()));
        ObjectNode body=valid.deepCopy();body.with("payload").put("content","secret");
        assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticleVisibilityChanged"),body.toString()));
        ObjectNode reason=valid.deepCopy();reason.with("payload").putObject("reason").put("private","secret");
        assertThrows(EventGuard.InvalidEvent.class,() -> guard.validate(claim("ArticleVisibilityChanged"),reason.toString()));
    }
}
