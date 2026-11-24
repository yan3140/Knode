package com.knode.content.snapshot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.knode.content.snapshot.SnapshotFailure.Code.*;
import static com.knode.content.snapshot.SnapshotRoutes.*;

@RestController
public class SnapshotController {
    private final SnapshotSettings settings;
    private final ArticleSnapshotRows articles;
    private final SnapshotExport exports;
    private final ArticleChangeFeed changes;

    public SnapshotController(SnapshotSettings settings, ArticleSnapshotRows articles,
                              SnapshotExport exports, ArticleChangeFeed changes) {
        this.settings = settings;
        this.articles = articles;
        this.exports = exports;
        this.changes = changes;
    }

    @PostMapping(VISIBILITY)
    public ResponseEntity<?> visibility(HttpServletRequest request) {
        authorize(request, VISIBILITY_READ);
        return response(articles.visibility(SnapshotRequests.visibility(request).ids()));
    }

    @PostMapping(NOTIFICATION_METADATA)
    public ResponseEntity<?> notificationMetadata(HttpServletRequest request) {
        SnapshotRoutes.requireOwner(request, NOTIFICATION_METADATA_READ);
        return response(articles.notificationMetadata(SnapshotRequests.batch(request)));
    }

    @PostMapping(CURRENT)
    public ResponseEntity<?> current(HttpServletRequest request) {
        authorize(request, SNAPSHOT_READ);
        return response(articles.current(SnapshotRequests.batch(request)));
    }

    @PostMapping(EXPORTS)
    public ResponseEntity<?> create(HttpServletRequest request) {
        String owner = authorize(request, SNAPSHOT_READ);
        return response(exports.create(owner, SnapshotRequests.create(request)));
    }

    @GetMapping(EXPORT_PAGE)
    public ResponseEntity<?> page(@PathVariable String snapshotId, HttpServletRequest request) {
        String owner = authorize(request, SNAPSHOT_READ);
        if (!SnapshotRequests.canonicalUuid(snapshotId)) throw new SnapshotFailure(INVALID_ARGUMENT);
        return response(exports.page(snapshotId, owner, SnapshotRequests.page(request, false)));
    }

    @GetMapping(CHANGES)
    public ResponseEntity<?> changes(HttpServletRequest request) {
        String owner = authorize(request, SNAPSHOT_READ);
        return response(changes.read(owner, SnapshotRequests.page(request, true)));
    }

    private String authorize(HttpServletRequest request, String permission) {
        String owner = SnapshotRoutes.requireOwner(request, permission);
        settings.requireEnabled();
        return owner;
    }

    private static ResponseEntity<?> response(Object body) {
        return ResponseEntity.ok().header("Cache-Control", SnapshotFailure.CACHE_POLICY).body(body);
    }

    @ExceptionHandler(SnapshotFailure.class)
    public ResponseEntity<?> failure(SnapshotFailure failure, HttpServletRequest request) {
        var response = ResponseEntity.status(failure.status()).header("Cache-Control", SnapshotFailure.CACHE_POLICY);
        if (failure.status() == 429) response.header("Retry-After", Integer.toString(SnapshotFailure.RETRY_AFTER_SECONDS));
        return response.body(failure.body(request));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unavailable(HttpServletRequest request) {
        return failure(new SnapshotFailure(DEPENDENCY_UNAVAILABLE), request);
    }
}
