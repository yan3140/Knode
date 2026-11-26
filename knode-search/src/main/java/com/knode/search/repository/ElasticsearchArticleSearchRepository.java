package com.knode.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.knode.search.client.CanonicalIdParser;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.model.QueryBudget;
import com.knode.search.model.SearchCandidatePage;
import com.knode.search.model.SearchCandidateRow;
import com.knode.search.model.SearchQuery;
import com.knode.search.model.SearchSort;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ElasticsearchArticleSearchRepository implements ArticleSearchRepository {

    private final ElasticsearchClient client;
    private final String indexName;
    private final long responseTimeoutMs;

    public ElasticsearchArticleSearchRepository(ElasticsearchClient client,
                                                SearchElasticsearchProperties properties) {
        if (!StringUtils.hasText(properties.getIndex())) {
            throw new IllegalStateException("knode.search.elasticsearch.index must be configured");
        }
        this.client = client;
        this.indexName = properties.getIndex().trim();
        this.responseTimeoutMs = Math.max(1L, properties.getResponseTimeoutMs());
    }

    @Override
    public SearchCandidatePage search(SearchQuery query, QueryBudget budget) throws IOException {
        Duration effectiveTimeout = budget.cap(Duration.ofMillis(responseTimeoutMs));
        if (effectiveTimeout.isZero() || effectiveTimeout.isNegative()) {
            throw new IOException("Search query timeout budget exhausted before Elasticsearch request");
        }
        SearchResponse<JsonNode> response = client.search(
                searchRequest(query, effectiveTimeout), JsonNode.class);
        if (budget.isExpired()) {
            throw new IOException("Search query timeout budget exhausted after Elasticsearch request");
        }
        return parseSearchPage(response);
    }

    SearchRequest searchRequest(SearchQuery query, Duration effectiveTimeout) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(indexName)
                .from(query.from())
                .size(query.pageSize())
                .timeout(effectiveTimeout.toMillis() + "ms")
                .trackTotalHits(track -> track.count(query.totalHitThreshold()))
                .query(q -> q.bool(bool -> bool
                        .must(must -> must.multiMatch(multiMatch -> multiMatch
                                .query(query.keyword())
                                .type(TextQueryType.BestFields)
                                .fields("title^5", "summary^3", "content", "categoryName^2", "tagNames^2")))
                        .mustNot(mustNot -> mustNot.term(term -> term
                                .field("searchable")
                                .value(false)))))
                .source(source -> source.filter(filter -> filter.includes(
                        "id", "sourceRevision", "title", "summary", "categoryId", "categoryName",
                        "tagNames", "thumbnail", "viewCount", "createTime")))
                .highlight(highlight -> highlight
                        .preTags("<em>")
                        .postTags("</em>")
                        .requireFieldMatch(false)
                        .fields(highlightFields(query.highlightContent())))
                .sort(sortOptions(query.sort()));
        return builder.build();
    }

    private List<NamedValue<HighlightField>> highlightFields(boolean highlightContent) {
        List<NamedValue<HighlightField>> fields = new ArrayList<>();
        fields.add(highlightField("title", 0, null));
        fields.add(highlightField("summary", 1, 160));
        if (highlightContent) {
            fields.add(highlightField("content", 1, 160));
        }
        fields.add(highlightField("categoryName", 0, null));
        fields.add(highlightField("tagNames", 0, null));
        return fields;
    }

    private NamedValue<HighlightField> highlightField(String name, int fragments, Integer fragmentSize) {
        HighlightField.Builder field = new HighlightField.Builder().numberOfFragments(fragments);
        if (fragmentSize != null) {
            field.fragmentSize(fragmentSize);
        }
        return NamedValue.of(name, field.build());
    }

    private List<SortOptions> sortOptions(SearchSort sort) {
        List<SortOptions> result = new ArrayList<>();
        if (sort == SearchSort.LATEST) {
            result.add(fieldSort("createTime"));
            result.add(scoreSort());
        } else if (sort == SearchSort.HOT) {
            result.add(fieldSort("viewCount"));
            result.add(scoreSort());
        } else {
            result.add(scoreSort());
            result.add(fieldSort("createTime"));
        }
        return result;
    }

    private SortOptions fieldSort(String field) {
        return SortOptions.of(sort -> sort.field(value -> value.field(field).order(SortOrder.Desc)));
    }

    private SortOptions scoreSort() {
        return SortOptions.of(sort -> sort.score(value -> value.order(SortOrder.Desc)));
    }

    private SearchCandidatePage parseSearchPage(SearchResponse<JsonNode> response) {
        long total = response.hits().total() == null
                ? response.hits().hits().size()
                : Math.max(0L, response.hits().total().value());
        List<SearchCandidateRow> rows = new ArrayList<>();
        for (Hit<JsonNode> hit : response.hits().hits()) {
            JsonNode source = hit.source();
            if (source == null || !source.isObject()) {
                continue;
            }
            Long id = parseArticleId(source.get("id"), hit.id());
            String sourceRevision = CanonicalIdParser.parseCanonicalPositiveFromJson(source.get("sourceRevision"));
            String title = textOrNull(source.get("title"));
            String summary = textOrNull(source.get("summary"));
            Long categoryId = CanonicalIdParser.parseLongFromJson(source.get("categoryId"));
            String categoryName = textOrNull(source.get("categoryName"));
            List<String> tagNames = parseTagNames(source.get("tagNames"));
            String thumbnail = textOrNull(source.get("thumbnail"));
            Long viewCount = CanonicalIdParser.parseLongFromJson(source.get("viewCount"));
            Long createTimeEpochMillis = parseCreateTimeEpochMillis(source.get("createTime"));
            Float score = hit.score() == null ? null : hit.score().floatValue();
            Map<String, List<String>> highlights = new LinkedHashMap<>(hit.highlight());

            rows.add(new SearchCandidateRow(
                    id,
                    sourceRevision,
                    title,
                    summary,
                    categoryId,
                    categoryName,
                    tagNames,
                    thumbnail,
                    viewCount == null ? 0L : viewCount,
                    createTimeEpochMillis,
                    score,
                    highlights
            ));
        }
        return new SearchCandidatePage(rows, total);
    }

    private static Long parseArticleId(JsonNode idNode, String hitId) {
        String fromSource = CanonicalIdParser.parseCanonicalPositiveFromJson(idNode);
        if (fromSource != null) {
            return Long.parseLong(fromSource);
        }
        return CanonicalIdParser.parsePositiveLongString(hitId);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : null;
    }

    private static List<String> parseTagNames(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : node) {
            if (element != null && element.isTextual()) {
                result.add(element.asText());
            }
        }
        return result;
    }

    private static Long parseCreateTimeEpochMillis(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                try {
                    return Instant.parse(raw).toEpochMilli();
                } catch (Exception ignoredInstant) {
                    return null;
                }
            }
        }
        return null;
    }
}
