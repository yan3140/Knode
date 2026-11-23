package com.knode.content.relay;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class EventGuard {
    public static final int MAX_BYTES=8_000_000;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(2_000_000).maxNestingDepth(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> COMMON=Set.of("eventId","eventType","schemaVersion","producer","aggregateType","aggregateId",
            "sourceRevision","publicationFactId","occurredAt","visibility","correlationId","traceContext","payload");
    private final FactTrace trace;
    public EventGuard(FactTrace trace) { this.trace=trace; }
    public void validate(RelayTransaction.Claim claim,String raw) {
        if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new InvalidEvent();
        try {
            JsonNode e=JSON.readTree(raw);
            if(!only(e,COMMON) || !claim.eventId().equals(text(e,"eventId")) || !claim.type().equals(text(e,"eventType"))
                    || !"knode-content".equals(text(e,"producer")) || !positive(text(e,"aggregateId"))
                    || !positive(text(e,"sourceRevision")) || text(e,"occurredAt")==null || !uuid(claim.eventId())
                    || !e.path("schemaVersion").isIntegralNumber() || !e.path("schemaVersion").canConvertToInt()) throw new InvalidEvent();
            java.time.Instant.parse(text(e,"occurredAt"));
            if(e.has("publicationFactId") && !e.get("publicationFactId").isNull()
                    && (text(e,"publicationFactId")==null || !text(e,"publicationFactId").matches("[A-Za-z0-9._:-]{1,128}"))) throw new InvalidEvent();
            if(e.has("correlationId") && (text(e,"correlationId")==null || !text(e,"correlationId").matches("[A-Za-z0-9._-]{1,64}"))) throw new InvalidEvent();
            if(e.has("traceContext")) {
                JsonNode ctx=e.get("traceContext");
                if(!only(ctx,Set.of("traceparent")) || ctx.size()!=1 || !trace.validStored(text(ctx,"traceparent"))) throw new InvalidEvent();
            }
            JsonNode payload=e.get("payload");
            if(!payloadIsObject(payload)) throw new InvalidEvent();
            int version=e.path("schemaVersion").intValue();
            switch(claim.family()) {
                case "ARTICLE" -> article(e,payload,version,claim.type());
                case "COMMENT" -> comment(e,payload,version,claim.type());
                case "COUNTER" -> counter(e,payload,version,claim.type());
                default -> throw new InvalidEvent();
            }
        } catch(InvalidEvent failure) { throw failure; }
        catch(Exception failure) { throw new InvalidEvent(); }
    }
    private static void article(JsonNode e,JsonNode p,int version,String type) {
        if(!only(e,COMMON) || !"ARTICLE".equals(text(e,"aggregateType")) || (version!=1 && version!=2)) throw new InvalidEvent();
        String visibility=text(e,"visibility");
        boolean publicFact="PUBLIC".equals(visibility);
        boolean validType=("ArticlePublished".equals(type) || "ArticleContentChanged".equals(type)) && publicFact
                || "ArticleVisibilityChanged".equals(type) && (publicFact || "WITHDRAWN".equals(visibility))
                || "ArticleDeleted".equals(type) && "DELETED".equals(visibility);
        if(!validType || !text(e,"aggregateId").equals(text(p,"articleId"))) throw new InvalidEvent();
        Set<String> allowed=publicFact?Set.of("articleId","authorId","title","summary","content","thumbnail","isTop","isComment",
                "category","tags","createTime","publishedAt","viewCount","counterRevision"):Set.of("articleId","reason");
        if(!only(p,allowed)) throw new InvalidEvent();
        if(publicFact) {
            if(text(e,"publicationFactId")==null) throw new InvalidEvent();
            if(!positive(text(p,"authorId")) || text(p,"title")==null || text(p,"title").length()>256
                    || text(p,"content")==null || text(p,"content").length()>1_000_000
                    || !nullableText(p,"summary",1024) || !nullableText(p,"thumbnail",2048)
                    || !binary(text(p,"isComment")) || text(p,"createTime")==null || text(p,"publishedAt")==null
                    || !nonnegative(text(p,"viewCount")) || !nonnegative(text(p,"counterRevision"))) throw new InvalidEvent();
            JsonNode category=p.get("category"),tags=p.get("tags");
            if(!only(category,Set.of("id","name")) || !positive(text(category,"id")) || text(category,"name")==null
                    || text(category,"name").length()>128 || tags==null || !tags.isArray() || tags.size()>50) throw new InvalidEvent();
            for(JsonNode tag:tags) if(!only(tag,Set.of("id","name")) || !positive(text(tag,"id"))
                    || text(tag,"name")==null || text(tag,"name").length()>256) throw new InvalidEvent();
            if((version==2 || p.has("isTop")) && !binary(text(p,"isTop"))) throw new InvalidEvent();
        } else if(p.has("reason") && !Set.of("UNPUBLISHED","DELETED").contains(text(p,"reason"))) throw new InvalidEvent();
    }
    private static void comment(JsonNode e,JsonNode p,int version,String type) {
        if(!only(e,Set.of("eventId","eventType","schemaVersion","producer","aggregateType","aggregateId","sourceRevision",
                    "occurredAt","correlationId","traceContext","payload"))
                || version!=1 || !"COMMENT".equals(text(e,"aggregateType")) || !Set.of("CommentCreated","CommentReplied").contains(type)
                || !only(p,Set.of("commentId","articleId","rootCommentId","toCommentId","actorId","receiverId","commentType","contentPreview"))
                || !text(e,"aggregateId").equals(text(p,"commentId")) || !"1".equals(text(e,"sourceRevision"))
                || !positive(text(p,"articleId")) || !positive(text(p,"actorId")) || !positive(text(p,"receiverId"))
                || !"0".equals(text(p,"commentType"))) throw new InvalidEvent();
        String preview=text(p,"contentPreview");
        if(preview==null || preview.codePointCount(0,preview.length())>500) throw new InvalidEvent();
        boolean root="-1".equals(text(p,"rootCommentId")) && "-1".equals(text(p,"toCommentId"));
        boolean reply=positive(text(p,"rootCommentId")) && positive(text(p,"toCommentId"));
        if("CommentCreated".equals(type) && !root || "CommentReplied".equals(type) && !reply) throw new InvalidEvent();
    }
    private static void counter(JsonNode e,JsonNode p,int version,String type) {
        if(!only(e,Set.of("eventId","eventType","schemaVersion","producer","aggregateType","aggregateId","sourceRevision",
                    "occurredAt","visibility","traceContext","payload"))
                || version!=1 || !"ArticleCounterChanged".equals(type) || !"ARTICLE_COUNTER".equals(text(e,"aggregateType"))
                || !Set.of("PUBLIC","WITHDRAWN","DELETED").contains(text(e,"visibility"))
                || !only(p,Set.of("articleId","viewCount","counterRevision"))
                || !text(e,"aggregateId").equals(text(p,"articleId")) || !nonnegative(text(p,"viewCount"))
                || !text(e,"sourceRevision").equals(text(p,"counterRevision"))) throw new InvalidEvent();
    }
    private static boolean binary(String s) { return "0".equals(s) || "1".equals(s); }
    private static boolean nullableText(JsonNode node,String name,int max) {
        JsonNode value=node.get(name);
        return value==null || value.isNull() || value.isTextual() && value.textValue().length()<=max;
    }
    private static boolean payloadIsObject(JsonNode n) { return n!=null && n.isObject(); }
    private static boolean only(JsonNode n,Set<String> allowed) {
        if(n==null || !n.isObject()) return false;
        Iterator<String> names=n.fieldNames();
        while(names.hasNext()) if(!allowed.contains(names.next())) return false;
        return true;
    }
    private static String text(JsonNode n,String name) {
        if(n==null || !n.isObject()) return null;
        JsonNode v=n.get(name);return v!=null && v.isTextual()?v.textValue():null;
    }
    private static boolean positive(String s) { return number(s) && !"0".equals(s); }
    private static boolean nonnegative(String s) { return number(s); }
    private static boolean number(String s) {
        if(s==null || !s.matches("0|[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(s)>=0; } catch(NumberFormatException failure) { return false; }
    }
    private static boolean uuid(String s) {
        try { return UUID.fromString(s).toString().equals(s); } catch(Exception failure) { return false; }
    }
    public static final class InvalidEvent extends RuntimeException {}
}
