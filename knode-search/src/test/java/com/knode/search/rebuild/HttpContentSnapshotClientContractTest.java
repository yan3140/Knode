package com.knode.search.rebuild;

import com.knode.search.client.ServiceTokenClient;
import com.knode.search.config.SearchClientsProperties;
import com.knode.search.model.QueryBudget;
import com.knode.search.rebuild.client.ChangeFeedPage;
import com.knode.search.rebuild.client.ContentSnapshotException;
import com.knode.search.rebuild.client.HttpContentSnapshotClient;
import com.knode.search.rebuild.client.SnapshotHandle;
import com.knode.search.rebuild.client.SnapshotPage;
import com.knode.search.support.LoopbackContentSnapshotStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpContentSnapshotClientContractTest {
    private LoopbackContentSnapshotStub stub;
    private HttpContentSnapshotClient client;
    private List<String> acquiredCalls;
    private List<String> invalidatedCalls;
    private AtomicReference<String> issuedToken;

    @BeforeEach
    void setUp() throws Exception {
        stub = new LoopbackContentSnapshotStub();
        acquiredCalls = new CopyOnWriteArrayList<>();
        invalidatedCalls = new CopyOnWriteArrayList<>();
        issuedToken = new AtomicReference<>("snapshot-service-token");
        stub.setExpectedBearerToken("snapshot-service-token");

        SearchClientsProperties props = new SearchClientsProperties();
        props.setContentOrigin(stub.baseUrl());
        props.setContentSnapshotTimeoutMs(5000L);

        ServiceTokenClient tokenClient = new ServiceTokenClient() {
            @Override
            public String acquireToken(String audience, List<String> permissions, QueryBudget budget, String requestId) {
                acquiredCalls.add(audience + ":" + String.join(",", permissions));
                return issuedToken.get();
            }

            @Override
            public void invalidate(String audience, List<String> permissions) {
                invalidatedCalls.add(audience + ":" + String.join(",", permissions));
            }
        };

        client = new HttpContentSnapshotClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                props,
                tokenClient
        );
    }

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    void acquiresSnapshotReadTokenOmitsCursorOnPage1AndHandlesEmptySnapshot() {
        stub.setLivePublicArticles(List.of());

        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(5));
        SnapshotHandle handle = client.createSearchRebuildSnapshot("req-empty-1", budget, "trace-1");
        assertThat(handle.snapshotId()).isNotBlank();
        assertThat(handle.incrementalCursor()).isNotBlank();
        assertThat(acquiredCalls).contains("knode-content:content:snapshot:read");

        SnapshotPage emptyPage = client.fetchSnapshotPage(handle.snapshotId(), null, 50, budget, "trace-2");
        assertThat(emptyPage.done()).isTrue();
        assertThat(emptyPage.nextCursor()).isNull();
        assertThat(emptyPage.items()).isEmpty();

        LoopbackContentSnapshotStub.RecordedHttpRequest pageReq = stub.requests().get(1);
        assertThat(pageReq.method()).isEqualTo("GET");
        assertThat(pageReq.query()).contains("limit=50");
        assertThat(pageReq.query()).doesNotContain("cursor=");
        assertThat(pageReq.authorization()).isEqualTo("Bearer snapshot-service-token");
    }

    @Test
    void paginatesMultiPageSnapshotAndInvalidatesServiceTokenOn401() {
        stub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(101L, 3L, "Title 101", "Sum 101", "Body 101",
                        10L, "Java", List.of("Spring"), 50L, 2L, "2026-10-01T00:00:00Z"),
                LoopbackContentSnapshotStub.snapshotItem(102L, 4L, "Title 102", "Sum 102", "Body 102",
                        11L, "Cloud", List.of("ES"), 80L, 3L, "2026-10-02T00:00:00Z")
        ));
        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(5));
        SnapshotHandle handle = client.createSearchRebuildSnapshot("req-multi-1", budget, "trace-create");

        SnapshotPage p1 = client.fetchSnapshotPage(handle.snapshotId(), null, 1, budget, "trace-p1");
        assertThat(p1.done()).isFalse();
        assertThat(p1.nextCursor()).isNotBlank();
        assertThat(p1.items()).hasSize(1);
        assertThat(p1.items().get(0).articleId()).isEqualTo(101L);

        // Simulate 401 token rejection -> invalidates cached token and throws retryable exception.
        issuedToken.set("expired-token");
        assertThatThrownBy(() -> client.fetchSnapshotPage(handle.snapshotId(), p1.nextCursor(), 1, budget, "trace-p2-fail"))
                .isInstanceOf(ContentSnapshotException.class)
                .satisfies(ex -> {
                    ContentSnapshotException cse = (ContentSnapshotException) ex;
                    assertThat(cse.httpStatus()).isEqualTo(401);
                    assertThat(cse.isRetryable()).isTrue();
                    assertThat(cse.isExpired410()).isFalse();
                });
        assertThat(invalidatedCalls).contains("knode-content:content:snapshot:read");

        // Fresh token succeeds and completes pagination.
        issuedToken.set("snapshot-service-token");
        SnapshotPage p2 = client.fetchSnapshotPage(handle.snapshotId(), p1.nextCursor(), 1, budget, "trace-p2-ok");
        assertThat(p2.done()).isTrue();
        assertThat(p2.nextCursor()).isNull();
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.items().get(0).articleId()).isEqualTo(102L);
    }

    @Test
    void classifiesHttp410AsExpiredAndRejectsMalformedPageOrChangesContracts() {
        stub.setLivePublicArticles(List.of(
                LoopbackContentSnapshotStub.snapshotItem(201L, 1L, "Alpha", "Sum", "Body",
                        null, null, List.of(), 0L, 0L, "2026-10-01T00:00:00Z")
        ));
        QueryBudget budget = QueryBudget.start(Duration.ofSeconds(5));
        SnapshotHandle handle = client.createSearchRebuildSnapshot("req-410", budget, "trace-410");

        stub.expireSnapshot(handle.snapshotId());
        assertThatThrownBy(() -> client.fetchSnapshotPage(handle.snapshotId(), null, 10, budget, "trace-snap-410"))
                .isInstanceOf(ContentSnapshotException.class)
                .satisfies(ex -> {
                    ContentSnapshotException cse = (ContentSnapshotException) ex;
                    assertThat(cse.httpStatus()).isEqualTo(410);
                    assertThat(cse.isExpired410()).isTrue();
                    assertThat(cse.errorCode()).isEqualTo("SNAPSHOT_EXPIRED");
                });

        stub.expireChangeCursor(handle.incrementalCursor());
        assertThatThrownBy(() -> client.fetchArticleChanges(handle.incrementalCursor(), 10, budget, "trace-chg-410"))
                .isInstanceOf(ContentSnapshotException.class)
                .satisfies(ex -> {
                    ContentSnapshotException cse = (ContentSnapshotException) ex;
                    assertThat(cse.httpStatus()).isEqualTo(410);
                    assertThat(cse.isExpired410()).isTrue();
                    assertThat(cse.errorCode()).isEqualTo("CHANGE_CURSOR_EXPIRED");
                });

        // Malformed response (done=true with non-null nextCursor) rejected as non-retryable protocol error.
        AtomicInteger injected = new AtomicInteger(0);
        stub.setInterceptor((req, def) -> {
            if (req.path().contains("/article-snapshots/") && injected.incrementAndGet() == 1) {
                return LoopbackContentSnapshotStub.HttpStubResponse.ok(
                        "{\"snapshotId\":\"" + handle.snapshotId() + "\",\"items\":[],\"nextCursor\":\"bad\",\"done\":true,\"incrementalCursor\":\"c1\"}");
            }
            return def.get();
        });
        assertThatThrownBy(() -> client.fetchSnapshotPage(handle.snapshotId(), null, 10, budget, "trace-bad"))
                .isInstanceOf(ContentSnapshotException.class)
                .satisfies(ex -> {
                    ContentSnapshotException cse = (ContentSnapshotException) ex;
                    assertThat(cse.isRetryable()).isFalse();
                    assertThat(cse.errorCode()).isEqualTo("INVALID_SNAPSHOT_PAGE");
                });

        stub.clearInterceptor();
        ChangeFeedPage emptyChanges = client.fetchArticleChanges(
                LoopbackContentSnapshotStub.encodeChangeCursor(0L, "fresh"), 20, budget, "trace-chg-ok");
        assertThat(emptyChanges.done()).isTrue();
        assertThat(emptyChanges.commits()).isEmpty();
        assertThat(emptyChanges.nextCursor()).isNotBlank();
    }
}
