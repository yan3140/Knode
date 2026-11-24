package com.knode.content.facade;

import com.knode.content.counter.CounterRead;
import com.knode.content.profile.UserProfileClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProfileFacadeService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.ofHours(8));

    private final JdbcTemplate db;
    private final UserProfileClient users;
    private final CommunityRelationClient relations;
    private final CounterRead counters;

    public ProfileFacadeService(
            JdbcTemplate db,
            UserProfileClient users,
            CommunityRelationClient relations,
            CounterRead counters) {
        this.db = db;
        this.users = users;
        this.relations = relations;
        this.counters = counters;
    }

    private record ArticleRow(
            long id,
            String title,
            String summary,
            long categoryId,
            String categoryName,
            String thumbnail,
            String status,
            long viewCount,
            String createdAt) {}

    public Map<String, Object> profile(
            String userIdText,
            String pageNumText,
            String pageSizeText,
            String viewerUserId,
            String viewerToken) {
        if (!OriginPolicy.validPositiveId(userIdText)) {
            throw new FacadeFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        }
        int pageNum = parsePositiveInt(pageNumText, 1);
        int pageSize = parsePositiveInt(pageSizeText, 10);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            throw new FacadeFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        }
        long offset = ((long) pageNum - 1) * pageSize;
        if (offset > 10_000_000L) {
            throw new FacadeFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        }

        UserProfileClient.DetailedProfile user = users.lookupDetailed(userIdText);
        if ("MISSING".equals(user.status())) {
            throw new FacadeFailure(200, 515, "\u7528\u6237\u4e0d\u5b58\u5728");
        }
        if (!"FOUND".equals(user.status())) {
            throw new FacadeFailure(503, 500, "\u7528\u6237\u8d44\u6599\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
        }

        Long total;
        List<ArticleRow> rows;
        try {
            total = db.queryForObject(
                    "SELECT COUNT(*) FROM content_article WHERE author_id=? AND status='0' AND deleted=0",
                    Long.class,
                    userIdText);
            rows = db.query(
                    "SELECT a.id,a.title,a.summary,a.category_id,c.name AS category_name,a.thumbnail,a.status,a.view_count,a.created_at "
                            + "FROM content_article a LEFT JOIN content_category c ON c.id=a.category_id "
                            + "WHERE a.author_id=? AND a.status='0' AND a.deleted=0 "
                            + "ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
                    this::mapRow,
                    userIdText,
                    pageSize,
                    offset);
        } catch (RuntimeException failure) {
            throw new FacadeFailure(503, 500, "\u5185\u5bb9\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
        }

        Map<Long, Long> liveCounts = counters.latest(rows.stream().map(ArticleRow::id).toList());
        List<Map<String, Object>> articleVos = new ArrayList<>(rows.size());
        for (ArticleRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Long.toString(row.id()));
            item.put("title", row.title());
            item.put("summary", row.summary());
            item.put("categoryId", Long.toString(row.categoryId()));
            item.put("categoryName", row.categoryName());
            item.put("thumbnail", row.thumbnail());
            item.put("status", row.status());
            item.put("viewCount", Long.toString(Math.max(row.viewCount(), liveCounts.getOrDefault(row.id(), row.viewCount()))));
            item.put("createTime", row.createdAt());
            item.put("authorId", userIdText);
            item.put("authorName", user.nickName());
            item.put("authorAvatar", user.avatar());
            item.put("authorInfoStatus", "FOUND");
            articleVos.add(item);
        }

        CommunityRelationClient.RelationSnapshot relation = relations.fetch(userIdText, viewerUserId, viewerToken);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", userIdText);
        profile.put("nickName", user.nickName());
        profile.put("avatar", user.avatar());
        profile.put("createTime", null);
        profile.put("profileRevision", user.profileRevision());
        profile.put("profileUpdatedAt", user.updatedAt());
        profile.put("userInfoStatus", "FOUND");
        profile.put("followingCount", relation.followingCount());
        profile.put("followerCount", relation.followerCount());
        profile.put("viewerFollowing", relation.viewerFollowing());
        profile.put("relationStatus", relation.relationStatus());
        profile.put("viewerFollowStatus", relation.viewerFollowStatus());
        profile.put("articles", Map.of("rows", articleVos, "total", Long.toString(Objects.requireNonNull(total))));
        return profile;
    }

    private ArticleRow mapRow(ResultSet rs, int index) throws SQLException {
        return new ArticleRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getString("thumbnail"),
                rs.getString("status"),
                rs.getLong("view_count"),
                DATE.format(rs.getTimestamp("created_at").toInstant()));
    }

    private static int parsePositiveInt(String text, int fallback) {
        if (text == null || text.isEmpty()) return fallback;
        if (!text.matches("[1-9][0-9]{0,9}")) {
            throw new FacadeFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new FacadeFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        }
    }
}
