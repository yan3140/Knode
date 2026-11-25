package com.knode.community.follow;

import com.knode.community.client.CommunityUserClient;
import com.knode.community.support.OriginPolicy;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class FollowService {
    private static final DateTimeFormatter GMT8_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.ofHours(8));
    private static final long MAX_OFFSET = 10_000_000L;
    private static final long READ_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final FollowTransaction tx;
    private final CommunityUserClient users;

    public FollowService(FollowTransaction tx, CommunityUserClient users) {
        this.tx = tx;
        this.users = users;
    }

    public void follow(String followerIdText, String followeeIdText) {
        long followerId = requirePositiveId(followerIdText);
        long followeeId = requirePositiveId(followeeIdText);
        if (followerId == followeeId) {
            throw CommunityBusinessFailure.paramError();
        }
        CommunityUserClient.TargetValidity validity = users.checkFollowTarget(Long.toString(followeeId));
        if (!validity.available()) {
            throw CommunityBusinessFailure.userUnavailable();
        }
        if (!validity.exists() || !validity.active()) {
            throw CommunityBusinessFailure.paramError();
        }
        try {
            tx.follow(followerId, followeeId);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public void unfollow(String followerIdText, String followeeIdText) {
        long followerId = requirePositiveId(followerIdText);
        long followeeId = requirePositiveId(followeeIdText);
        try {
            tx.unfollow(followerId, followeeId);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public Map<String, Object> followingStatus(String followerIdText, String followeeIdText) {
        long followerId = requirePositiveId(followerIdText);
        long followeeId = requirePositiveId(followeeIdText);
        try {
            boolean following = tx.isFollowing(followerId, followeeId);
            return Map.of("following", following);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    public Map<String, Object> following(String followerIdText, String pageNumText, String pageSizeText) {
        return followList(followerIdText, pageNumText, pageSizeText, true, false);
    }

    public Map<String, Object> followers(String followeeIdText, String pageNumText, String pageSizeText) {
        return followList(followeeIdText, pageNumText, pageSizeText, false, false);
    }

    public Map<String, Object> followingOf(String targetUserIdText, String pageNumText, String pageSizeText) {
        return followList(targetUserIdText, pageNumText, pageSizeText, true, true);
    }

    private Map<String, Object> followList(String userIdText, String pageNumText, String pageSizeText,
                                           boolean following, boolean publicList) {
        long deadline = System.nanoTime() + READ_BUDGET_NANOS;
        long userId = requirePositiveId(userIdText);
        PageSpec spec = parsePage(pageNumText, pageSizeText);
        List<FollowTransaction.FollowRow> rawRows;
        try {
            rawRows = tx.scanFollowList(userId, following);
        } catch (RuntimeException dbFailure) {
            throw CommunityBusinessFailure.communityUnavailable();
        }

        Set<String> ids = new LinkedHashSet<>();
        if (publicList) {
            ids.add(userIdText);
        }
        rawRows.forEach(row -> ids.add(Long.toString(row.userId())));
        Map<String, CommunityUserClient.AccountValidity> accounts = requireValidity(ids, deadline);
        if (publicList && !accounts.get(userIdText).exists()) {
            throw CommunityBusinessFailure.paramError();
        }
        List<FollowTransaction.FollowRow> eligible = rawRows.stream()
                .filter(row -> accounts.get(Long.toString(row.userId())).eligible())
                .toList();
        int from = (int) Math.min(spec.offset(), eligible.size());
        int to = Math.min(from + spec.pageSize(), eligible.size());
        return assembleFollowPage(eligible.subList(from, to), eligible.size(), deadline);
    }

    public List<FollowTransaction.RelationBatchItem> relationsBatch(List<Long> distinctUserIds, Long viewerUserId) {
        long deadline = System.nanoTime() + READ_BUDGET_NANOS;
        FollowTransaction.RelationSnapshot snapshot = tx.scanRelations(distinctUserIds);
        Set<String> ids = new LinkedHashSet<>();
        snapshot.outgoing().forEach(edge -> ids.add(Long.toString(edge.followeeId())));
        snapshot.incoming().forEach(edge -> ids.add(Long.toString(edge.followerId())));
        Map<String, CommunityUserClient.AccountValidity> accounts = requireValidity(ids, deadline);

        Map<Long, Long> followingCounts = new HashMap<>();
        Map<Long, Long> followerCounts = new HashMap<>();
        Set<Long> viewerFollowed = new HashSet<>();
        for (FollowTransaction.FollowEdge edge : snapshot.outgoing()) {
            if (accounts.get(Long.toString(edge.followeeId())).eligible()) {
                followingCounts.merge(edge.followerId(), 1L, Long::sum);
            }
        }
        for (FollowTransaction.FollowEdge edge : snapshot.incoming()) {
            if (accounts.get(Long.toString(edge.followerId())).eligible()) {
                followerCounts.merge(edge.followeeId(), 1L, Long::sum);
            }
            if (viewerUserId != null && viewerUserId == edge.followerId()) {
                viewerFollowed.add(edge.followeeId());
            }
        }
        requireBudget(deadline);
        return distinctUserIds.stream().map(id -> new FollowTransaction.RelationBatchItem(
                Long.toString(id),
                Long.toString(followingCounts.getOrDefault(id, 0L)),
                Long.toString(followerCounts.getOrDefault(id, 0L)),
                viewerUserId == null || viewerUserId.equals(id) ? null : viewerFollowed.contains(id))).toList();
    }

    private Map<String, CommunityUserClient.AccountValidity> requireValidity(Set<String> ids, long deadline) {
        requireBudget(deadline);
        CommunityUserClient.ValidityBatch result = users.lookupValidity(ids, deadline);
        if (!result.available()) {
            throw CommunityBusinessFailure.userUnavailable();
        }
        requireBudget(deadline);
        return result.accounts();
    }

    private Map<String, Object> assembleFollowPage(List<FollowTransaction.FollowRow> rows, int total, long deadline) {
        requireBudget(deadline);
        if (rows.isEmpty()) {
            return Map.of("rows", List.of(), "total", Integer.toString(total));
        }
        List<String> targetIds = rows.stream()
                .map(row -> Long.toString(row.userId()))
                .toList();
        CommunityUserClient.ProfileBatch profiles = users.lookupProfiles(targetIds, deadline);
        if (!profiles.available()) {
            throw CommunityBusinessFailure.userUnavailable();
        }
        List<Map<String, Object>> vos = new ArrayList<>(rows.size());
        for (FollowTransaction.FollowRow row : rows) {
            String uid = Long.toString(row.userId());
            CommunityUserClient.PublicProfile profile = profiles.profiles().get(uid);
            if (profile == null || !"FOUND".equals(profile.status())) {
                throw CommunityBusinessFailure.userUnavailable();
            }
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("userId", uid);
            vo.put("nickName", profile.nickName());
            vo.put("avatar", profile.avatar());
            vo.put("followTime", GMT8_DATE.format(row.followTime()));
            vo.put("userInfoStatus", profile.status());
            vos.add(vo);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", vos);
        out.put("total", Integer.toString(total));
        requireBudget(deadline);
        return out;
    }

    private static void requireBudget(long deadline) {
        if (System.nanoTime() - deadline >= 0) {
            throw CommunityBusinessFailure.communityUnavailable();
        }
    }

    private record PageSpec(int pageSize, long offset) {}

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
        return new PageSpec(pageSize, offset);
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
}
