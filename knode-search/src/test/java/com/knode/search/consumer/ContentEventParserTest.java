package com.knode.search.consumer;

import com.knode.search.consumer.event.ArticleVisibility;
import com.knode.search.consumer.event.ContentEventParser;
import com.knode.search.consumer.event.ParsedContentEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentEventParserTest {
    private final ContentEventParser parser = new ContentEventParser();

    @Test
    void parsesArticleV1AndV2IncludingLegacyNullFirstPublicationMetadata() {
        String v1Json = """
                {
                  "eventId": "evt-art-v1-1",
                  "eventType": "ArticlePublished",
                  "schemaVersion": 1,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "1001",
                  "sourceRevision": "1",
                  "publicationFactId": "pub-1001",
                  "occurredAt": "2026-11-26T06:00:00Z",
                  "producer": "knode-content",
                  "visibility": "PUBLIC",
                  "payload": {
                    "articleId": "1001",
                    "authorId": "50",
                    "title": "Spring Cloud Search",
                    "summary": "Summary text",
                    "content": "Full body content",
                    "thumbnail": null,
                    "isComment": "1",
                    "category": {"id": "10", "name": "Backend"},
                    "tags": [{"id": "1", "name": "Java"}, {"id": "2", "name": "ES"}],
                    "createTime": "2026-11-20T01:02:03Z",
                    "publishedAt": "2026-11-26T06:00:00Z",
                    "viewCount": "42",
                    "counterRevision": "3"
                  }
                }
                """;
        ContentEventParser.ParseResult r1 = parser.parseStreamFields(Map.of("event", v1Json));
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.event()).isInstanceOf(ParsedContentEvent.PublicArticleEvent.class);
        ParsedContentEvent.PublicArticleEvent e1 = (ParsedContentEvent.PublicArticleEvent) r1.event();
        assertThat(e1.articleId()).isEqualTo(1001L);
        assertThat(e1.sourceRevision()).isEqualTo(1L);
        assertThat(e1.viewCount()).isEqualTo(42L);
        assertThat(e1.counterRevision()).isEqualTo(3L);
        assertThat(e1.tagNames()).containsExactly("Java", "ES");

        // Legacy v2 non-first public update allows null publicationFactId & publishedAt and requires isTop.
        String v2LegacyUpdate = """
                {
                  "eventId": "evt-art-v2-2",
                  "eventType": "ArticleContentChanged",
                  "schemaVersion": 2,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "1001",
                  "sourceRevision": "2",
                  "publicationFactId": null,
                  "occurredAt": "2026-11-26T06:10:00Z",
                  "producer": "knode-content",
                  "visibility": "PUBLIC",
                  "payload": {
                    "articleId": "1001",
                    "authorId": "50",
                    "title": "Updated v2 Title",
                    "summary": "Updated summary",
                    "content": "Updated body",
                    "thumbnail": "https://cdn.example/a.png",
                    "isTop": "1",
                    "isComment": "1",
                    "category": {"id": "10", "name": "Backend"},
                    "tags": [],
                    "createTime": "2026-11-20T01:02:03Z",
                    "publishedAt": null,
                    "viewCount": 12,
                    "counterRevision": 1
                  }
                }
                """;
        ContentEventParser.ParseResult r2 = parser.parseStreamFields(Map.of("event", v2LegacyUpdate));
        assertThat(r2.isSuccess()).isTrue();
        ParsedContentEvent.PublicArticleEvent e2 = (ParsedContentEvent.PublicArticleEvent) r2.event();
        assertThat(e2.schemaVersion()).isEqualTo(2);
        assertThat(e2.isTop()).isEqualTo("1");
        assertThat(e2.sourceRevision()).isEqualTo(2L);
    }

    @Test
    void parsesCounterAndIgnoredCommentAndRejectsForbiddenBodyAndMalformedPayloads() {
        String counterJson = """
                {
                  "eventId": "evt-cnt-1",
                  "eventType": "ArticleCounterChanged",
                  "schemaVersion": 1,
                  "aggregateType": "ARTICLE_COUNTER",
                  "aggregateId": "1001",
                  "sourceRevision": "5",
                  "publicationFactId": null,
                  "occurredAt": "2026-11-26T06:20:00Z",
                  "producer": "knode-content",
                  "visibility": "PUBLIC",
                  "payload": {
                    "articleId": "1001",
                    "viewCount": "99",
                    "counterRevision": "5"
                  }
                }
                """;
        ContentEventParser.ParseResult counterRes = parser.parseJson(counterJson);
        assertThat(counterRes.isSuccess()).isTrue();
        ParsedContentEvent.CounterChangedEvent counter = (ParsedContentEvent.CounterChangedEvent) counterRes.event();
        assertThat(counter.viewCount()).isEqualTo(99L);
        assertThat(counter.counterRevision()).isEqualTo(5L);
        assertThat(counter.visibility()).isEqualTo(ArticleVisibility.PUBLIC);

        String commentJson = """
                {
                  "eventId": "evt-cmt-1",
                  "eventType": "CommentCreated",
                  "schemaVersion": 1,
                  "aggregateType": "COMMENT",
                  "aggregateId": "9001",
                  "sourceRevision": "1",
                  "occurredAt": "2026-11-26T06:21:00Z",
                  "producer": "knode-content",
                  "payload": {
                    "commentId": "9001",
                    "articleId": "1001",
                    "rootCommentId": "-1",
                    "toCommentId": "-1",
                    "actorId": "50",
                    "receiverId": "50",
                    "commentType": "0",
                    "contentPreview": "Nice post!"
                  }
                }
                """;
        ContentEventParser.ParseResult commentRes = parser.parseJson(commentJson);
        assertThat(commentRes.isSuccess()).isTrue();
        assertThat(commentRes.event()).isInstanceOf(ParsedContentEvent.IgnoredNonSearchEvent.class);

        // Hidden event carrying body must fail with FORBIDDEN_BODY_ON_HIDDEN_EVENT and retain eventId.
        String badHidden = """
                {
                  "eventId": "evt-bad-hidden-1",
                  "eventType": "ArticleDeleted",
                  "schemaVersion": 2,
                  "aggregateType": "ARTICLE",
                  "aggregateId": "1001",
                  "sourceRevision": "8",
                  "occurredAt": "2026-11-26T06:22:00Z",
                  "producer": "knode-content",
                  "visibility": "DELETED",
                  "payload": {
                    "articleId": "1001",
                    "visibility": "DELETED",
                    "sourceRevision": "8",
                    "content": "LEAKED BODY"
                  }
                }
                """;
        ContentEventParser.ParseResult badHiddenRes = parser.parseJson(badHidden);
        assertThat(badHiddenRes.isSuccess()).isFalse();
        assertThat(badHiddenRes.failure().eventId()).isEqualTo("evt-bad-hidden-1");
        assertThat(badHiddenRes.failure().errorCode()).isEqualTo("FORBIDDEN_BODY_ON_HIDDEN_EVENT");
        assertThat(badHiddenRes.failure().failureKey("1-1")).isEqualTo("event:evt-bad-hidden-1");

        // Malformed JSON missing eventId falls back to stream:<recordId> key.
        ContentEventParser.ParseResult brokenJson = parser.parseJson("{broken");
        assertThat(brokenJson.isSuccess()).isFalse();
        assertThat(brokenJson.failure().eventId()).isNull();
        assertThat(brokenJson.failure().errorCode()).isEqualTo("INVALID_JSON");
        assertThat(brokenJson.failure().failureKey("170000-1")).isEqualTo("stream:170000-1");

        // Extra stream field rejected.
        ContentEventParser.ParseResult extraField = parser.parseStreamFields(
                Map.of("event", counterJson, "extra", "unexpected"));
        assertThat(extraField.isSuccess()).isFalse();
        assertThat(extraField.failure().errorCode()).isEqualTo("UNEXPECTED_STREAM_FIELDS");
    }
}
