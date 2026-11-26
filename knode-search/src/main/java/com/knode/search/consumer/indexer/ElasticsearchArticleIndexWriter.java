package com.knode.search.consumer.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.GetResponse;
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
    private final String indexName;
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
        this.indexName = esProperties.getIndex().trim();
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

        String docId = Long.toString(task.articleId());
        long version = task.targetProjectionRevision();
        try {
            elasticsearchClient.index(req -> req
                    .index(indexName)
                    .id(docId)
                    .version(version)
                    .versionType(VersionType.External)
                    .refresh(refreshPolicy)
                    .document(payloadNode));
            return IndexWriteResult.applied();
        } catch (ElasticsearchException esEx) {
            if (esEx.status() == 409) {
                return verifyConflict(docId, version, task.operation(), expectedDoc);
            }
            return IndexWriteResult.failed("ES_HTTP_" + esEx.status());
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            if (status == 409) {
                return verifyConflict(docId, version, task.operation(), expectedDoc);
            }
            return IndexWriteResult.failed("ES_HTTP_" + status);
        } catch (Exception ex) {
            return IndexWriteResult.failed("ES_IO_" + ex.getClass().getSimpleName());
        }
    }

    private IndexWriteResult verifyConflict(
            String docId,
            long expectedVersion,
            SearchIndexOperation operation,
            SearchIndexDocument expectedDoc) {
        try {
            GetResponse<JsonNode> current = elasticsearchClient.get(req -> req
                    .index(indexName)
                    .id(docId), JsonNode.class);
            if (!current.found() || current.version() == null) {
                return IndexWriteResult.conflictUnverified("ES_409_DOC_NOT_FOUND_OR_NO_VERSION");
            }
            long actualVersion = current.version();
            if (actualVersion > expectedVersion) {
                return IndexWriteResult.superseded();
            }
            if (actualVersion == expectedVersion) {
                JsonNode source = current.source();
                if (matchesExpectedDocument(source, operation, expectedDoc)) {
                    return IndexWriteResult.idempotentApplied();
                }
                return IndexWriteResult.conflictUnverified("ES_409_SAME_VERSION_CONTENT_MISMATCH");
            }
            return IndexWriteResult.conflictUnverified("ES_409_LOWER_VERSION_OBSERVED:" + actualVersion);
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
