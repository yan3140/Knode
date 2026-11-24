package com.knode.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.content.article.ArticleCommand;
import com.knode.content.article.ArticleCommandService;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"KNODE_MANAGEMENT_PORT=0", "KNODE_CONTENT_PORT=0"})
@Import(AuthenticationHttpTest.Fixtures.class)
class NotificationMetadataHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "/internal/v1/content/articles/notification-metadata:batch";
    private static final String PERM = "content:notification-metadata:read";
    private static final String COMMUNITY = "knode-community";
    private static final String ACTOR = "9007199254740993";
    private static final String DB = "jdbc:h2:mem:content_g10_notif;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000";
    private static final String RING = "test-key=" + AuthenticationHttpTest.PUBLIC_KEY + ";overlap-key=" + AuthenticationHttpTest.PUBLIC_KEY;

    @LocalServerPort int port;
    @SpyBean JdbcTemplate db;
    @Autowired ArticleCommandService articles;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        AuthenticationHttpTest.properties(p);
        p.add("KNODE_CONTENT_JDBC_URL", () -> DB);
        p.add("KNODE_CONTENT_OPS_PUBLIC_KEYS", () -> RING);
        // Explicitly keep KNODE_CONTENT_SNAPSHOT_API_ENABLED at default false to verify independence from G6 export config.
        p.add("KNODE_CONTENT_SNAPSHOT_API_ENABLED", () -> false);
    }

    @BeforeEach
    void setup() throws Exception {
        Mockito.reset(db);
        try (Connection connection = db.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:content_g10_notif"));
        }
        db.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("db/content-article-schema.sql")).execute(db.getDataSource());
        db.update("INSERT INTO content_category(id,name,status) VALUES(2,'Java','0')");
        db.update("INSERT INTO content_tag(id,name,del_flag) VALUES(3,'Spring',0)");
    }

    @Test
    void mixedBatchReturnsExpectedStatusesTitlesAndSingleMinimalSqlQuery() throws Exception {
        long pubId = create("Public Visible Title", "0");
        long draftId = create("Secret Draft Title", "1");
        long withdrawnId = create("Secret Withdrawn Title", "0");
        articles.setStatus(ACTOR, withdrawnId, "1", null);
        long deletedId = create("Secret Deleted Title", "0");
        articles.delete(ACTOR, deletedId, null);
        long highPublicId = 9_007_199_254_740_993L;
        insertHighIdArticle(highPublicId, "High Int64 Public Title", "0", 0, 12L);
        long missingHighId = Long.MAX_VALUE;

        List<String> expectedOrder = List.of(
                Long.toString(deletedId),
                Long.toString(highPublicId),
                Long.toString(withdrawnId),
                Long.toString(draftId),
                Long.toString(pubId),
                Long.toString(missingHighId));
        List<String> inputWithDuplicates = new ArrayList<>(expectedOrder);
        inputWithDuplicates.add(1, Long.toString(deletedId));
        inputWithDuplicates.add(Long.toString(highPublicId));

        AtomicInteger queryCount = new AtomicInteger();
        List<String> executedSql = new ArrayList<>();
        Mockito.doAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            queryCount.incrementAndGet();
            executedSql.add(sql);
            return inv.callRealMethod();
        }).when(db).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        HttpResponse<String> response = send("POST", PATH, service(COMMUNITY, PERM),
                JSON.writeValueAsString(Map.of("articleIds", inputWithDuplicates)));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());

        // Single SQL query projecting only minimal fields (never reading content, category, or tags)
        assertEquals(1, queryCount.get());
        String sqlLower = executedSql.get(0).toLowerCase();
        assertFalse(sqlLower.contains("a.content"), sqlLower);
        assertFalse(sqlLower.contains("content_category"), sqlLower);
        assertFalse(sqlLower.contains("content_tag"), sqlLower);

        JsonNode items = JSON.readTree(response.body()).path("items");
        assertEquals(expectedOrder.size(), items.size());
        assertEquals(expectedOrder, fieldValues(items, "articleId"));
        assertEquals(List.of("DELETED", "PUBLIC", "WITHDRAWN", "DRAFT", "PUBLIC", "MISSING"), fieldValues(items, "visibility"));
        assertEquals(List.of("DENIED", "READABLE", "DENIED", "DENIED", "READABLE", "DENIED"), fieldValues(items, "decision"));
        assertEquals(List.of("2", "12", "2", "1", "1", "null"), fieldValues(items, "sourceRevision"));

        // PUBLIC items carry title; all DENIED items omit title completely; MISSING sourceRevision is JSON null
        assertFalse(items.get(0).has("title"));
        assertEquals("High Int64 Public Title", items.get(1).path("title").textValue());
        assertFalse(items.get(2).has("title"));
        assertFalse(items.get(3).has("title"));
        assertEquals("Public Visible Title", items.get(4).path("title").textValue());
        assertFalse(items.get(5).has("title"));
        assertTrue(items.get(5).has("sourceRevision") && items.get(5).get("sourceRevision").isNull());

        // No non-public title or article body leaked anywhere in raw HTTP response
        assertFalse(response.body().contains("Secret Draft Title"));
        assertFalse(response.body().contains("Secret Withdrawn Title"));
        assertFalse(response.body().contains("Secret Deleted Title"));
        assertFalse(response.body().contains("body "));
    }

    @Test
    void renameUpdatesTitleAndWithdrawOmitsTitleForSameArticle() throws Exception {
        long articleId = create("Initial Title", "0");
        String body = JSON.writeValueAsString(Map.of("articleIds", List.of(Long.toString(articleId))));
        String token = service(COMMUNITY, PERM);

        JsonNode first = ok(send("POST", PATH, token, body)).path("items").get(0);
        assertEquals("PUBLIC", first.path("visibility").asText());
        assertEquals("READABLE", first.path("decision").asText());
        assertEquals("1", first.path("sourceRevision").asText());
        assertEquals("Initial Title", first.path("title").asText());

        // Rename article -> returns updated title and incremented sourceRevision
        articles.edit(ACTOR, articleId, new ArticleCommand("Renamed Title", null, "1", "updated body", List.of(3L), 2L, "sum", "0"), null);
        JsonNode renamed = ok(send("POST", PATH, token, body)).path("items").get(0);
        assertEquals("PUBLIC", renamed.path("visibility").asText());
        assertEquals("READABLE", renamed.path("decision").asText());
        assertEquals("2", renamed.path("sourceRevision").asText());
        assertEquals("Renamed Title", renamed.path("title").asText());

        // Withdraw article -> WITHDRAWN/DENIED, title omitted, neither old nor new title leaked
        articles.setStatus(ACTOR, articleId, "1", null);
        HttpResponse<String> withdrawnResp = send("POST", PATH, token, body);
        JsonNode withdrawn = ok(withdrawnResp).path("items").get(0);
        assertEquals("WITHDRAWN", withdrawn.path("visibility").asText());
        assertEquals("DENIED", withdrawn.path("decision").asText());
        assertEquals("3", withdrawn.path("sourceRevision").asText());
        assertFalse(withdrawn.has("title"));
        assertFalse(withdrawnResp.body().contains("Initial Title"));
        assertFalse(withdrawnResp.body().contains("Renamed Title"));
    }

    @Test
    void validatesBatchSizeBoundsInt64IdsAndStrictJsonShape() throws Exception {
        String token = service(COMMUNITY, PERM);

        // Exactly 100 IDs (including > 2^53 safe int values) accepted
        List<String> hundred = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            hundred.add(Long.toString(9_007_199_254_740_993L + i));
        }
        JsonNode hundredResp = ok(send("POST", PATH, token, JSON.writeValueAsString(Map.of("articleIds", hundred))));
        assertEquals(100, hundredResp.path("items").size());
        assertEquals("9007199254740993", hundredResp.path("items").get(0).path("articleId").textValue());
        assertEquals("9007199254741092", hundredResp.path("items").get(99).path("articleId").textValue());

        // Invalid inputs -> 400 INVALID_ARGUMENT
        for (String badBody : List.of(
                "{}",
                "{\"articleIds\":[]}",
                "{\"articleIds\":[101]}",
                "{\"articleIds\":[\"0\"]}",
                "{\"articleIds\":[\"-1\"]}",
                "{\"articleIds\":[\"01\"]}",
                "{\"articleIds\":[\"9223372036854775808\"]}",
                "{\"articleIds\":[\"abc\"]}",
                "{\"articleIds\":[null]}",
                "{\"articleIds\":[\"101\"],\"purpose\":\"PUBLIC_SEARCH\"}",
                "{\"articleIds\":[\"101\"],\"viewerId\":\"1\"}",
                "{\"articleIds\":[\"101\"],\"articleIds\":[\"102\"]}",
                "{\"articleIds\":[\"101\"]} {}",
                JSON.writeValueAsString(Map.of("articleIds", Collections.nCopies(101, "101"))))) {
            error(400, "INVALID_ARGUMENT", send("POST", PATH, token, badBody));
        }
        error(400, "INVALID_ARGUMENT", send("POST", PATH + "?extra=1", token, "{\"articleIds\":[\"101\"]}"));

        // Non-JSON content-type rejected 400
        HttpRequest nonJson = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"articleIds\":[\"101\"]}"))
                .build();
        error(400, "INVALID_ARGUMENT", AuthenticationHttpTest.HTTP.send(nonJson, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void enforcesMachineAuthBoundariesAndReturns503OnDatabaseFailure() throws Exception {
        String valid = service(COMMUNITY, PERM);
        String body = "{\"articleIds\":[\"101\"]}";

        // Missing, invalid, user, wrong-audience, untrusted-kid tokens -> 401
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, null, body));
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, "not-a-jwt", body));
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, machine(COMMUNITY, "knode-user", "test-key", List.of(PERM)), body));
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, machine(COMMUNITY, "knode-content", "untrusted", List.of(PERM)), body));
        String userToken = AuthenticationHttpTest.token("blog", 600);
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, userToken, body));
        error(401, "INVALID_SERVICE_TOKEN", sendWithHeader("POST", PATH, null, body, "token", userToken));
        error(401, "INVALID_SERVICE_TOKEN", sendWithHeader("POST", PATH, valid, body, "token", userToken));

        // Overlapping trusted kid -> 200
        assertEquals(200, send("POST", PATH, machine(COMMUNITY, "knode-content", "overlap-key", List.of(PERM)), body).statusCode());

        // Insufficient permissions -> 403; empty permissions claim is invalid service token -> 401
        for (String wrongPerm : List.of("content:visibility:read", "content:snapshot:read", "ops:read")) {
            error(403, "SERVICE_PERMISSION_DENIED", send("POST", PATH, service(COMMUNITY, wrongPerm), body));
        }
        error(401, "INVALID_SERVICE_TOKEN", send("POST", PATH, machine(COMMUNITY, "knode-content", "test-key", List.of()), body));

        // Forbidden identity / viewer / operator / mixed headers -> 401
        for (String header : List.of("X-User-Id", "X-Viewer-Id", "X-Operator", "Cookie")) {
            error(401, "INVALID_SERVICE_TOKEN", sendWithHeader("POST", PATH, valid, body, header, "forged"));
        }
        error(401, "INVALID_SERVICE_TOKEN", sendWithHeader("POST", PATH, valid, body, "Authorization", "Bearer " + valid));

        // Wrong HTTP methods and path variants rejected
        error(401, "INVALID_SERVICE_TOKEN", send("GET", PATH, valid, null));
        assertEquals(401, send("HEAD", PATH, valid, null).statusCode());
        for (String badPath : List.of(PATH + ";", PATH + "/", PATH.replace("metadata:", "metadata%3A"), PATH.replace("/content/", "//content/"))) {
            assertTrue(send("POST", badPath, valid, body).statusCode() >= 400, badPath);
        }

        // Database failure -> 503 DEPENDENCY_UNAVAILABLE
        db.execute("ALTER TABLE content_article RENAME TO fixture_unavailable_article");
        error(503, "DEPENDENCY_UNAVAILABLE", send("POST", PATH, valid, body));
    }

    private long create(String title, String status) {
        return articles.create(ACTOR, new ArticleCommand(title, null, "1", "body " + title, List.of(3L), 2L, "summary", status), null);
    }

    private void insertHighIdArticle(long id, String title, String status, int deleted, long revision) {
        Timestamp now = Timestamp.from(Instant.parse("2026-11-20T00:00:00Z"));
        db.update("""
                INSERT INTO content_article(id,title,content,category_id,is_top,is_comment,status,author_id,created_at,updated_at,
                    updated_by,view_count,counter_revision,source_revision,publication_fact_id,published_at,deleted)
                VALUES(?,?,?,2,'0','1',?,?,?,?,?,0,0,?,?,?,?)
                """, id, title, "body " + title, status, ACTOR, now, now, ACTOR, revision, UUID.randomUUID().toString(), now, deleted);
    }

    private static List<String> fieldValues(JsonNode items, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode v = item.get(field);
            out.add(v == null || v.isNull() ? "null" : v.asText());
        }
        return out;
    }

    private static String service(String subject, String... perms) {
        return machine(subject, "knode-content", "test-key", List.of(perms));
    }

    private static String machine(String subject, String aud, String kid, List<String> perms) {
        Instant now = Instant.now();
        return Jwts.builder().header().keyId(kid).and().issuer("knode-user").subject(subject)
                .audience().add(aud).and().claim("token_use", "service").claim("permissions", perms)
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now.minusSeconds(2)))
                .expiration(Date.from(now.plusSeconds(120)))
                .signWith(AuthenticationHttpTest.KEYS.getPrivate()).compact();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static void error(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(code, body.path("error").path("code").asText());
        assertTrue(body.path("requestId").isTextual());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        return sendWithHeader(method, path, token, body, null, null);
    }

    private HttpResponse<String> sendWithHeader(String method, String path, String token, String body, String hName, String hVal) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (hName != null) b.header(hName, hVal);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return AuthenticationHttpTest.HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
