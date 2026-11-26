package com.knode.search.consumer.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knode.search.config.SearchElasticsearchProperties;
import com.knode.search.consumer.config.SearchConsumerProperties;
import com.knode.search.consumer.persistence.SearchIndexDocument;
import com.knode.search.consumer.persistence.SearchIndexOperation;
import com.knode.search.consumer.persistence.SearchIndexTaskRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "knode.search.consumer.indexer", name = "enabled", havingValue = "true")
public class ElasticsearchArticleIndexWriter implements ArticleIndexWriter {
    private static final List<String> TOMBSTONE_FORBIDDEN_FIELDS = List.of(
            "title", "summary", "content", "categoryName", "tagNames", "thumbnail"
    );

    private final ElasticsearchClient elasticsearchClient;
    private final String defaultIndexName;
    private final Refresh refreshPolicy;
    private final ObjectMapper objectMapper;

    public ElasticsearchArticleIndexWriter(
            ElasticsearchClient elasticsearchClient,
            SearchElasticsearchProperties esProperties,
            SearchConsumerProperties consumerProperties,
            ObjectMapper objectMapper) {
        if (!StringUtils.hasText(esProperties.getIndex())) {
            throw new IllegalStateException("knode.search.elasticsearch.index must be configured");
        }
        this.elasticsearchClient = elasticsearchClient;
        this.defaultIndexName = esProperties.getIndex().trim();
        this.refreshPolicy = resolveRefresh(consumerProperties.getIndexer().getRefreshPolicy());
        this.objectMapper = objectMapper;
    }

    @Override
    public IndexWriteResult write(SearchIndexTaskRecord task) {
        JsonNode payloadNode;
        SearchIndexDocument expectedDoc;
        try {
            payloadNode = objectMapper.readTree(task.payloadJson());
            expectedDoc = objectMapper.treeToValue(payloadNode, SearchIndexDocument.class);
        } catch (Exception ex) {
            return IndexWriteResult.failed("INVALID_STORED_PAYLOAD:" + ex.getClass().getSimpleName());
        }

        String targetIndex = StringUtils.hasText(task.targetIndex())
                ? task.targetIndex().trim()
                : defaultIndexName;
        String docId = Long.toString(task.articleId());
        long version = task.targetProjectionRevision();
        try {
            elasticsearchClient.index(req -> req
                    .index(targetIndex)
                    .id(docId)
                    .version(version)
                    .versionType(VersionType.External)
                    .refresh(refreshPolicy)
                    .document(payloadNode));
            return IndexWriteResult.applied();
        } catch (ElasticsearchException esEx) {
            if (esEx.status() == 409) {
                return verifyConflictOnSearchPath(targetIndex, docId, version, task.operation(), expectedDoc);
            }
            return IndexWriteResult.failed("ES_HTTP_" + esEx.status());
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            if (status == 409) {
                return verifyConflictOnSearchPath(targetIndex, docId, version, task.operation(), expectedDoc);
            }
            return IndexWriteResult.failed("ES_HTTP_" + status);
        } catch (Exception ex) {
            return IndexWriteResult.failed("ES_IO_" + ex.getClass().getSimpleName());
        }
    }

    /**
     * Verifies a 409 conflict strictly on the refreshed target search path (POST /{targetIndex}/_search
     * with ids query and version=true) instead of realtime GET, so unrefreshed writes remain retryable
     * and never advance the search cache epoch prematurely.
     */
    private IndexWriteResult verifyConflictOnSearchPath(
            String targetIndex,
            String docId,
            long expectedVersion,
            SearchIndexOperation operation,
            SearchIndexDocument expectedDoc) {
        try {
            SearchResponse<JsonNode> response = elasticsearchClient.search(req -> req
                    .index(targetIndex)
                    .size(1)
                    .version(true)
                    .query(q -> q.ids(ids -> ids.values(docId))), JsonNode.class);
            Hit<JsonNode> matchedHit = null;
            if (response.hits() != null && response.hits().hits() != null) {
                for (Hit<JsonNode> hit : response.hits().hits()) {
                    if (hit != null && docId.equals(hit.id())) {
                        matchedHit = hit;
                        break;
                    }
                }
            }
            if (matchedHit == null) {
                return IndexWriteResult.conflictUnverified("ES_409_NOT_YET_SEARCH_VISIBLE");
            }
            Long visibleVersion = matchedHit.version();
            if (visibleVersion == null) {
                return IndexWriteResult.conflictUnverified("ES_409_SEARCH_HIT_MISSING_VERSION");
            }
            if (visibleVersion > expectedVersion) {
                return IndexWriteResult.superseded();
            }
            if (visibleVersion == expectedVersion) {
                JsonNode source = matchedHit.source();
                if (matchesExpectedDocument(source, operation, expectedDoc)) {
                    return IndexWriteResult.idempotentApplied();
                }
                return IndexWriteResult.conflictUnverified("ES_409_SAME_VERSION_CONTENT_MISMATCH");
            }
            return IndexWriteResult.conflictUnverified("ES_409_SEARCH_STALE_VERSION:" + visibleVersion);
        } catch (ElasticsearchException esEx) {
            return IndexWriteResult.conflictUnverified("ES_409_VERIFY_HTTP_" + esEx.status());
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            return IndexWriteResult.conflictUnverified("ES_409_VERIFY_HTTP_" + status);
        } catch (Exception ex) {
            return IndexWriteResult.conflictUnverified("ES_409_VERIFY_" + ex.getClass().getSimpleName());
        }
    }

    private static boolean matchesExpectedDocument(
            JsonNode source,
            SearchIndexOperation operation,
            SearchIndexDocument expected) {
        if (source == null || !source.isObject()) {
            return false;
        }
        Long actualId = readLong(source.get("id"));
        String actualRev = readString(source.get("sourceRevision"));
        Boolean actualSearchable = readBoolean(source.get("searchable"));
        if (!Objects.equals(actualId, expected.id())
                || !Objects.equals(actualRev, expected.sourceRevision())
                || !Objects.equals(actualSearchable, expected.searchable())) {
            return false;
        }
        if (operation == SearchIndexOperation.TOMBSTONE) {
            if (Boolean.TRUE.equals(actualSearchable)) {
                return false;
            }
            for (String forbidden : TOMBSTONE_FORBIDDEN_FIELDS) {
                JsonNode node = source.get(forbidden);
                if (node != null && !node.isNull()) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(readString(source.get("title")), expected.title())
                && Objects.equals(readString(source.get("summary")), expected.summary())
                && Objects.equals(readString(source.get("content")), expected.content())
                && Objects.equals(readLong(source.get("categoryId")), expected.categoryId())
                && Objects.equals(readString(source.get("categoryName")), expected.categoryName())
                && Objects.equals(readStringList(source.get("tagNames")),
                        expected.tagNames() == null ? List.of() : expected.tagNames())
                && Objects.equals(readString(source.get("thumbnail")), expected.thumbnail())
                && Objects.equals(readLong(source.get("viewCount")), expected.viewCount())
                && Objects.equals(readLong(source.get("createTime")), expected.createTime());
    }

    private static Refresh resolveRefresh(String policy) {
        if (policy != null && "true".equals(policy.trim().toLowerCase(Locale.ROOT))) {
            return Refresh.True;
        }
        return Refresh.WaitFor;
    }

    private static String readString(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Boolean readBoolean(JsonNode node) {
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    private static Long readLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                list.add(item.asText());
            }
        }
        return list;
    }
}
