package com.knode.content.snapshot;

import com.knode.auth.ServiceTokenVerifier;
import com.knode.content.relay.MachineAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.knode.content.snapshot.SnapshotFailure.Code.*;

public final class SnapshotRoutes {
    static final String ROOT = "/internal/v1/content";
    static final String VISIBILITY = ROOT + "/articles/visibility:batch";
    static final String NOTIFICATION_METADATA = ROOT + "/articles/notification-metadata:batch";
    static final String CURRENT = ROOT + "/articles/snapshots:batch";
    static final String EXPORTS = ROOT + "/article-snapshots";
    static final String EXPORT_PAGE = EXPORTS + "/{snapshotId}";
    static final String CHANGES = ROOT + "/article-changes";
    static final String VISIBILITY_READ = "content:visibility:read";
    static final String NOTIFICATION_METADATA_READ = "content:notification-metadata:read";
    static final String SNAPSHOT_READ = "content:snapshot:read";
    private static final Pattern EXPORT_ID = Pattern.compile(EXPORTS + "/[A-Za-z0-9_-]{1,64}");

    private SnapshotRoutes() {}

    public static String permission(HttpServletRequest request) {
        String path = request.getRequestURI();
        return switch (request.getMethod()) {
            case "POST" -> VISIBILITY.equals(path) ? VISIBILITY_READ
                    : NOTIFICATION_METADATA.equals(path) ? NOTIFICATION_METADATA_READ
                    : CURRENT.equals(path) || EXPORTS.equals(path) ? SNAPSHOT_READ : null;
            case "GET" -> CHANGES.equals(path) || EXPORT_ID.matcher(path).matches() ? SNAPSHOT_READ : null;
            default -> null;
        };
    }

    public static boolean inNamespace(HttpServletRequest request) {
        return inNamespace(request.getRequestURI()) || inNamespace(request.getServletPath()) || inNamespace(request.getPathInfo());
    }

    private static boolean inNamespace(String path) {
        return path != null && (path.equals(ROOT) || path.startsWith(ROOT + "/") || path.startsWith(ROOT + ";"));
    }

    public static boolean viewerHeaderPresent(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .map(header -> header.toLowerCase(Locale.ROOT))
                .anyMatch(header -> header.equals("x-viewer") || header.startsWith("x-viewer-") || header.equals("viewerid"));
    }

    static String requireOwner(HttpServletRequest request, String required) {
        Object value = request.getAttribute(MachineAuth.PRINCIPAL);
        if (!(value instanceof ServiceTokenVerifier.Principal principal) || !required.equals(permission(request))) {
            throw new SnapshotFailure(INVALID_SERVICE_TOKEN);
        }
        if (!"knode-content".equals(principal.audience()) || principal.permissions() == null
                || !principal.permissions().contains(required) || principal.service() == null
                || !principal.service().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new SnapshotFailure(SERVICE_PERMISSION_DENIED);
        }
        return principal.service();
    }
}
