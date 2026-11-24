package com.knode.content.snapshot;

import com.knode.auth.ServiceTokenVerifier;
import com.knode.content.relay.MachineAuth;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.knode.content.snapshot.SnapshotCursor.Kind.CHANGES;
import static com.knode.content.snapshot.SnapshotRequests.Purpose.SEARCH_REBUILD;
import static com.knode.content.snapshot.SnapshotRoutes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotBoundaryTest {
    @Test
    void handlerChecksVerifiedOwnerPermissionAndExactRawMethodBeforeAnyRead() {
        ArticleSnapshotRows rows = mock(ArticleSnapshotRows.class);
        SnapshotExport exports = mock(SnapshotExport.class);
        ArticleChangeFeed feed = mock(ArticleChangeFeed.class);
        SnapshotController controller = new SnapshotController(new SnapshotSettings(false,true,"",""), rows, exports, feed);
        var request = new MockHttpServletRequest("POST",VISIBILITY);

        assertEquals(401,assertThrows(SnapshotFailure.class, () -> controller.visibility(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-search","knode-content",Set.of(SNAPSHOT_READ),1));
        assertEquals(403,assertThrows(SnapshotFailure.class, () -> controller.visibility(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-search","other",Set.of(VISIBILITY_READ),1));
        assertEquals(403,assertThrows(SnapshotFailure.class, () -> controller.visibility(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-search","knode-content",Set.of(VISIBILITY_READ),1));
        assertEquals(503,assertThrows(SnapshotFailure.class, () -> controller.visibility(request)).status());
        request.setMethod("HEAD");
        assertEquals(401,assertThrows(SnapshotFailure.class, () -> controller.visibility(request)).status());
        verifyNoInteractions(rows,exports,feed);
    }

    @Test
    void notificationMetadataHandlerEnforcesOwnerPermissionAudienceMethodAndDoesNotRequireSnapshotExportToggle() {
        ArticleSnapshotRows rows = mock(ArticleSnapshotRows.class);
        SnapshotExport exports = mock(SnapshotExport.class);
        ArticleChangeFeed feed = mock(ArticleChangeFeed.class);
        SnapshotController controller = new SnapshotController(new SnapshotSettings(false,true,"",""), rows, exports, feed);
        var request = new MockHttpServletRequest("POST", NOTIFICATION_METADATA);
        request.setContentType("application/json");
        request.setContent("{\"articleIds\":[\"101\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(401, assertThrows(SnapshotFailure.class, () -> controller.notificationMetadata(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-community", "knode-content", Set.of(VISIBILITY_READ), 1));
        assertEquals(403, assertThrows(SnapshotFailure.class, () -> controller.notificationMetadata(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-community", "other", Set.of(NOTIFICATION_METADATA_READ), 1));
        assertEquals(403, assertThrows(SnapshotFailure.class, () -> controller.notificationMetadata(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,
                new ServiceTokenVerifier.Principal("knode-community", "knode-content", Set.of(NOTIFICATION_METADATA_READ), 1));
        request.setMethod("GET");
        assertEquals(401, assertThrows(SnapshotFailure.class, () -> controller.notificationMetadata(request)).status());
        verifyNoInteractions(rows, exports, feed);

        request.setMethod("POST");
        when(rows.notificationMetadata(java.util.List.of(101L)))
                .thenReturn(new ArticleSnapshotRows.Batch<>(java.util.List.of(
                        new ArticleSnapshotRows.NotificationMetadataItem("101", "1",
                                ArticleSnapshotRows.Visibility.PUBLIC, ArticleSnapshotRows.Decision.READABLE, "Hello"))));
        var ok = controller.notificationMetadata(request);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("no-store", ok.getHeaders().getFirst("Cache-Control"));
        verify(rows).notificationMetadata(java.util.List.of(101L));
        verifyNoInteractions(exports, feed);
    }

    @Test
    void disablingAnOldExportGenerationRequiresRebootstrapEvenWithSameKey() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String epoch = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,() -> new SnapshotSettings(true,true,epoch,""));
        assertThrows(IllegalArgumentException.class,() -> new SnapshotSettings(true,true,"",key));

        SnapshotCursor original = new SnapshotCursor(new SnapshotSettings(true,true,epoch,key));
        SnapshotCursor replacement = new SnapshotCursor(new SnapshotSettings(true,true,UUID.randomUUID().toString(),key));
        Instant clock = Instant.parse("2026-01-01T00:00:00Z");
        String token = original.issue(CHANGES,"knode-search",SEARCH_REBUILD,null,17,clock.plusSeconds(60));
        assertEquals(17,original.open(token,CHANGES,"knode-search",clock).offset());
        assertEquals(410,assertThrows(SnapshotFailure.class, () -> replacement.open(token,CHANGES,"knode-search",clock)).status());
        assertEquals(410,assertThrows(SnapshotFailure.class, () -> original.open(token,CHANGES,"knode-search",clock.plusSeconds(60))).status());
    }
}
