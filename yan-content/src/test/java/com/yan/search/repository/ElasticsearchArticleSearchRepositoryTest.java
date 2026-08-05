package com.yan.search.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.util.NamedValue;
import com.yan.search.config.ElasticsearchProperties;
import com.yan.search.model.ArticleSearchDocument;
import com.yan.search.model.SearchQuery;
import com.yan.search.model.SearchSort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

class ElasticsearchArticleSearchRepositoryTest {

    @Test
    void searchRequestShouldUseWeightsHighlightsPaginationAndHotSort() {
        ElasticsearchArticleSearchRepository repository = new ElasticsearchArticleSearchRepository(
                mock(ElasticsearchClient.class), new ElasticsearchProperties());

        SearchRequest request = repository.searchRequest(new SearchQuery(
                "Spring Boot", 2, 10, SearchSort.HOT, false, 10_000, true, "cache-key"));

        assertThat(request.from()).isEqualTo(10);
        assertThat(request.size()).isEqualTo(10);
        assertThat(request.trackTotalHits().count()).isEqualTo(10_000);
        assertThat(request.query().multiMatch().fields())
                .containsExactly("title^5", "summary^3", "content", "categoryName^2", "tagNames^2");
        assertThat(request.highlight().preTags()).containsExactly("<em>");
        assertThat(request.highlight().fields().stream().map(NamedValue::name).toList())
                .doesNotContain("content");
        assertThat(request.sort().get(0).field().field()).isEqualTo("viewCount");
        assertThat(request.sort().get(0).field().order()).isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
    }

    @Test
    void indexDefinitionShouldUseOfficialSmartChineseAnalyzer() {
        ElasticsearchArticleSearchRepository repository = new ElasticsearchArticleSearchRepository(
                mock(ElasticsearchClient.class), new ElasticsearchProperties());

        CreateIndexRequest definition = repository.indexDefinition();

        assertThat(definition.mappings().properties().get("title").text().analyzer())
                .isEqualTo("smartcn");
        assertThat(definition.mappings().properties().get("content").text().searchAnalyzer())
                .isEqualTo("smartcn");
        assertThat(definition.settings().numberOfReplicas()).isEqualTo("0");
    }

    @Test
    void ensureIndexShouldCreateIndexOnlyOnceWhenIndexDoesNotExist() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        BooleanResponse exists = mock(BooleanResponse.class);
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(ExistsRequest.class))).thenReturn(exists);
        when(exists.value()).thenReturn(false);
        when(indices.create(any(CreateIndexRequest.class))).thenReturn(mock(CreateIndexResponse.class));
        ElasticsearchArticleSearchRepository repository = new ElasticsearchArticleSearchRepository(
                client, new ElasticsearchProperties());

        repository.ensureIndex();
        repository.ensureIndex();

        verify(indices, times(1)).exists(any(ExistsRequest.class));
        verify(indices, times(1)).create(any(CreateIndexRequest.class));
    }

    @Test
    void bulkSynchronizeRequestShouldContainIndexAndDeleteOperations() {
        ElasticsearchArticleSearchRepository repository = new ElasticsearchArticleSearchRepository(
                mock(ElasticsearchClient.class), new ElasticsearchProperties());
        ArticleSearchDocument document = new ArticleSearchDocument(1L, "标题", "摘要", "正文", 2L,
                "Java", List.of("Spring"), null, 10L, 1_700_000_000_000L);

        BulkRequest request = repository.bulkSynchronizeRequest(List.of(document), List.of(2L));

        assertThat(request.operations()).hasSize(2);
        assertThat(request.operations().get(0).index().id()).isEqualTo("1");
        assertThat(request.operations().get(1).delete().id()).isEqualTo("2");
    }
}
