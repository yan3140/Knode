package com.knode.community.notification;

import com.knode.community.client.CommunityUserClient;
import com.knode.community.client.ContentNotificationMetadataClient;
import com.knode.community.follow.CommunityBusinessFailure;
import com.knode.community.support.OriginPolicy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private static final DateTimeFormatter GMT8_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.ofHours(8));
    private static final long MAX_OFFSET = 10_000_000L;

    private final NotificationTransaction tx;
    private final CommunityUserClient users;
    private final ContentNotificationMetadataClient contentMetadata;
    private final Clock clock;

    public NotificationService(
            NotificationTransaction tx,
            CommunityUserClient users,
            ContentNotificationMetadataClient contentMetadata,
            Clock clock) {
        this.tx = tx;
        this.users = users;
        this.contentMetadata = contentMetadata;
        this.clock = clock;
    }

    public Map<String, Object> inbox(String receiverIdText, String pageNumText, String pageSizeText) {
        long receiverId = requirePositiveId(receiverIdText);
        PageSpec spec = parsePage(pageNumText, pageSizeText);
        NotificationTransaction.InboxPage page;
        try {
            page = tx.selectInbox(receiverId, spec.pageSize(), spec.offset());
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
        if (page.rows().isEmpty()) {
            return Map.of("rows", List.of(), "total", Long.toString(page.total()));
        }

        LinkedHashSet<String> actorIds = new LinkedHashSet<>();
        LinkedHashSet<String> articleIds = new LinkedHashSet<>();
        for (NotificationTransaction.RawNotificationRow row : page.rows()) {
            if (row.actorId() != null && row.actorId() > 0L) {
                actorIds.add(Long.toString(row.actorId()));
            }
            if (row.articleId() != null && row.articleId() > 0L) {
                articleIds.add(Long.toString(row.articleId()));
            }
        }

        // External lookups execute strictly outside any database transaction.
        CommunityUserClient.ProfileBatch actorProfiles = actorIds.isEmpty()
                ? new CommunityUserClient.ProfileBatch(true, Map.of())
                : users.lookupProfiles(actorIds);
        ContentNotificationMetadataClient.MetadataBatch articleMetas = articleIds.isEmpty()
                ? new ContentNotificationMetadataClient.MetadataBatch(true, Map.of())
                : contentMetadata.lookup(articleIds);

        List<Map<String, Object>> vos = new ArrayList<>(page.rows().size());
        for (NotificationTransaction.RawNotificationRow row : page.rows()) {
            vos.add(toVo(row, actorProfiles, articleMetas));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", vos);
        out.put("total", Long.toString(page.total()));
        return out;
    }

    public Map<String, Object> unreadCount(String receiverIdText) {
        long receiverId = requirePositiveId(receiverIdText);
        try {
            long count = tx.countUnread(receiverId);
            return Map.of("unreadCount", Long.toString(count));
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public void markRead(String receiverIdText, String notificationIdText) {
        long receiverId = requirePositiveId(receiverIdText);
        long notificationId = requireNonZeroSignedId(notificationIdText);
        try {
            tx.markRead(receiverId, notificationId);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public void markAllRead(String receiverIdText) {
        long receiverId = requirePositiveId(receiverIdText);
        try {
            tx.markAllRead(receiverId);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public void delete(String receiverIdText, String notificationIdText) {
        long receiverId = requirePositiveId(receiverIdText);
        long notificationId = requireNonZeroSignedId(notificationIdText);
        try {
            tx.delete(receiverId, notificationId);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public NotificationTransaction.NotificationSummaryDto summary(long receiverId) {
        return tx.querySummary(receiverId, clock.instant());
    }

    private static Map<String, Object> toVo(
            NotificationTransaction.RawNotificationRow row,
            CommunityUserClient.ProfileBatch actorProfiles,
            ContentNotificationMetadataClient.MetadataBatch articleMetas) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", Long.toString(row.id()));
        vo.put("type", row.type());
        vo.put("content", row.content());
        vo.put("isRead", row.isRead());
        vo.put("createTime", GMT8_DATE.format(row.createTime()));

        if (row.actorId() == null || row.actorId() <= 0L) {
            vo.put("actorId", null);
            vo.put("actorNickName", null);
            vo.put("actorAvatar", null);
            vo.put("actorInfoStatus", "NOT_APPLICABLE");
        } else {
            String actorIdStr = Long.toString(row.actorId());
            vo.put("actorId", actorIdStr);
            if (!actorProfiles.available()) {
                vo.put("actorNickName", null);
                vo.put("actorAvatar", null);
                vo.put("actorInfoStatus", "UNAVAILABLE");
            } else {
                CommunityUserClient.PublicProfile profile = actorProfiles.profiles().get(actorIdStr);
                if (profile == null) {
                    vo.put("actorNickName", null);
                    vo.put("actorAvatar", null);
                    vo.put("actorInfoStatus", "UNAVAILABLE");
                } else {
                    vo.put("actorNickName", profile.nickName());
                    vo.put("actorAvatar", profile.avatar());
                    vo.put("actorInfoStatus", profile.status());
                }
            }
        }

        if (row.articleId() == null || row.articleId() <= 0L) {
            vo.put("articleId", null);
            vo.put("articleTitle", null);
            vo.put("articleVisibilityStatus", "NOT_APPLICABLE");
        } else {
            String articleIdStr = Long.toString(row.articleId());
            vo.put("articleId", articleIdStr);
            if (!articleMetas.available()) {
                // Fail-closed: never expose stale cached titles when authoritative visibility is unreachable.
                vo.put("articleTitle", null);
                vo.put("articleVisibilityStatus", "TEMP_UNAVAILABLE");
            } else {
                ContentNotificationMetadataClient.ArticleMetadata meta = articleMetas.items().get(articleIdStr);
                if (meta == null) {
                    vo.put("articleTitle", null);
                    vo.put("articleVisibilityStatus", "TEMP_UNAVAILABLE");
                } else if (meta.readablePublic()) {
                    vo.put("articleTitle", meta.title());
                    vo.put("articleVisibilityStatus", "PUBLIC");
                } else {
                    vo.put("articleTitle", null);
                    vo.put("articleVisibilityStatus", "UNAVAILABLE");
                }
            }
        }

        vo.put("commentId", row.commentId() == null ? null : Long.toString(row.commentId()));
        vo.put("rootCommentId", row.rootCommentId() == null ? null : Long.toString(row.rootCommentId()));
        return vo;
    }

    private record PageSpec(int pageNum, int pageSize, long offset) {}

    private static PageSpec parsePage(String pageNumText, String pageSizeText) {
        int pageNum = parsePositiveInt(pageNumText, 1);
        int pageSize = parsePositiveInt(pageSizeText, 10);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw CommunityBusinessFailure.paramError();
        }
        long offset = ((long) pageNum - 1L) * pageSize;
        if (offset > MAX_OFFSET) {
            throw CommunityBusinessFailure.paramError();
        }
        return new PageSpec(pageNum, pageSize, offset);
    }

    private static int parsePositiveInt(String text, int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        if (!text.matches("[1-9][0-9]{0,9}")) {
            throw CommunityBusinessFailure.paramError();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw CommunityBusinessFailure.paramError();
        }
    }

    private static long requirePositiveId(String raw) {
        long parsed = OriginPolicy.parsePositiveIdOrZero(raw);
        if (parsed <= 0L) {
            throw CommunityBusinessFailure.paramError();
        }
        return parsed;
    }

    private static long requireNonZeroSignedId(String raw) {
        Long parsed = OriginPolicy.parseNonZeroSignedId(raw);
        if (parsed == null) {
            throw CommunityBusinessFailure.paramError();
        }
        return parsed;
    }
}
