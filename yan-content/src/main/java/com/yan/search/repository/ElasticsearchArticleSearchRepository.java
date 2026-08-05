package com.yan.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.util.NamedValue;
import com.yan.domain.vo.SearchArticleVo;
import com.yan.search.config.ElasticsearchProperties;
import com.yan.search.model.ArticleSearchDocument;
import com.yan.search.model.ArticleSearchPage;
import com.yan.search.model.SearchQuery;
import com.yan.search.model.SearchSort;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class ElasticsearchArticleSearchRepository implements ArticleSearchRepository {

    private final ElasticsearchClient client;
    private final String indexName;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public ElasticsearchArticleSearchRepository(ElasticsearchClient client,
                                                ElasticsearchProperties properties) {
        this.client = client;
        this.indexName = properties.getIndexName();
    }

    @Override
    public synchronized void ensureIndex() throws IOException {
        if (indexReady.get()) {
            return;
        }
        ExistsRequest existsRequest = ExistsRequest.of(request -> request.index(indexName));
        if (client.indices().exists(existsRequest).value()) {
            indexReady.set(true);
            return;
        }
        try {
            client.indices().create(indexDefinition());
        } catch (ElasticsearchException exception) {
            if (exception.status() != 400
                    || !"resource_already_exists_exception".equals(exception.error().type())) {
                throw exception;
            }
        }
        indexReady.set(true);
    }

    @Override
    public Set<Long> bulkSynchronize(List<ArticleSearchDocument> documents,
                                     Collection<Long> deleteArticleIds) throws IOException {
        ensureIndex();
        BulkRequest request = bulkSynchronizeRequest(documents, deleteArticleIds);
        if (request.operations().isEmpty()) {
            return Set.of();
        }
        BulkResponse response = client.bulk(request);
        return response.items().stream()
                .filter(item -> item.error() != null)
                .map(item -> Long.valueOf(item.id()))
                .collect(Collectors.toSet());
    }

    @Override
    public void rebuild(List<ArticleSearchDocument> documents) throws IOException {
        ensureIndex();
        if (!documents.isEmpty()) {
            BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
            documents.forEach(document -> bulk.operations(operation -> operation.index(index -> index
                    .index(indexName)
                    .id(String.valueOf(document.id()))
                    .document(document))));
            BulkResponse response = client.bulk(bulk.build());
            if (response.errors()) {
                throw new IOException("Elasticsearch bulk rebuild contains failed items");
            }
        }
        deleteStaleDocuments(documents);
    }

    @Override
    public ArticleSearchPage search(SearchQuery query) throws IOException {
        ensureIndex();
        SearchResponse<ArticleSearchDocument> response = client.search(
                searchRequest(query), ArticleSearchDocument.class);
        return parseSearchPage(response);
    }

    SearchRequest searchRequest(SearchQuery query) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(indexName)
                .from(query.from())
                .size(query.pageSize())
                .trackTotalHits(track -> track.count(query.totalHitThreshold()))
                .query(value -> value.multiMatch(multiMatch -> multiMatch
                        .query(query.keyword())
                        .type(TextQueryType.BestFields)
                        .fields("title^5", "summary^3", "content", "categoryName^2", "tagNames^2")))
                .source(source -> source.filter(filter -> filter.includes(
                        "id", "title", "summary", "categoryId", "categoryName", "tagNames",
                        "thumbnail", "viewCount", "createTime")))
                .highlight(highlight -> highlight
                        .preTags("<em>")
                        .postTags("</em>")
                        .requireFieldMatch(false)
                        .fields(
                                highlightField("title", 0, null),
                                highlightField("summary", 1, 160),
                                highlightField("categoryName", 0, null),
                                highlightField("tagNames", 0, null)))
                .sort(sortOptions(query.sort()));
        if (query.highlightContent()) {
            builder.highlight(highlight -> highlight
                    .preTags("<em>")
                    .postTags("</em>")
                    .requireFieldMatch(false)
                    .fields(
                            highlightField("title", 0, null),
                            highlightField("summary", 1, 160),
                            highlightField("content", 1, 160),
                            highlightField("categoryName", 0, null),
                            highlightField("tagNames", 0, null)));
        }
        return builder.build();
    }

    BulkRequest bulkSynchronizeRequest(List<ArticleSearchDocument> documents,
                                       Collection<Long> deleteArticleIds) {
        BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
        documents.forEach(document -> bulk.operations(operation -> operation.index(index -> index
                .index(indexName)
                .id(String.valueOf(document.id()))
                .document(document))));
        deleteArticleIds.stream().distinct().forEach(articleId -> bulk.operations(operation -> operation.delete(delete -> delete
                .index(indexName)
                .id(String.valueOf(articleId)))));
        return bulk.build();
    }

    CreateIndexRequest indexDefinition() {
        return new CreateIndexRequest.Builder()
                .index(indexName)
                .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0"))
                .mappings(mapping -> mapping
                        .properties("id", property -> property.long_(number -> number))
                        .properties("title", this::textProperty)
                        .properties("summary", this::textProperty)
                        .properties("content", this::textProperty)
                        .properties("categoryId", property -> property.long_(number -> number))
                        .properties("categoryName", this::textProperty)
                        .properties("tagNames", this::textProperty)
                        .properties("thumbnail", property -> property.keyword(keyword -> keyword.index(false)))
                        .properties("viewCount", property -> property.long_(number -> number))
                        .properties("createTime", property -> property.date(date -> date.format("epoch_millis"))))
                .build();
    }

    private co.elastic.clients.util.ObjectBuilder<co.elastic.clients.elasticsearch._types.mapping.Property>
    textProperty(co.elastic.clients.elasticsearch._types.mapping.Property.Builder property) {
        return property.text(text -> text.analyzer("smartcn").searchAnalyzer("smartcn"));
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

    private void deleteStaleDocuments(List<ArticleSearchDocument> documents) throws IOException {
        Query query;
        if (documents.isEmpty()) {
            query = Query.of(value -> value.matchAll(matchAll -> matchAll));
        } else {
            List<String> currentIds = documents.stream()
                    .map(document -> String.valueOf(document.id()))
                    .toList();
            query = Query.of(value -> value.bool(bool -> bool
                    .mustNot(mustNot -> mustNot.ids(ids -> ids.values(currentIds)))));
        }
        client.deleteByQuery(request -> request
                .index(indexName)
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .query(query));
    }

    private ArticleSearchPage parseSearchPage(SearchResponse<ArticleSearchDocument> response) {
        long total = response.hits().total() == null
                ? response.hits().hits().size()
                : response.hits().total().value();
        List<SearchArticleVo> rows = new ArrayList<>();
        for (Hit<ArticleSearchDocument> hit : response.hits().hits()) {
            ArticleSearchDocument source = hit.source();
            if (source == null) {
                continue;
            }
            rows.add(new SearchArticleVo(
                    source.id(),
                    source.title(),
                    source.summary(),
                    source.categoryId(),
                    source.categoryName(),
                    source.tagNames(),
                    source.thumbnail(),
                    source.viewCount(),
                    source.createTime() == null ? null : new Date(source.createTime()),
                    hit.score() == null ? null : hit.score().floatValue(),
                    new LinkedHashMap<>(hit.highlight())
            ));
        }
        return new ArticleSearchPage(rows, total);
    }
}
