package com.knode.search.rebuild.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "knode.search.rebuild", name = "enabled", havingValue = "true")
public class ElasticsearchIndexAdminClient implements SearchIndexAdminClient {
    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchIndexAdminClient(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    @Override
    public void ensurePhysicalIndex(String physicalIndex) {
        if (!StringUtils.hasText(physicalIndex)) {
            throw new IllegalArgumentException("physicalIndex must be non-blank");
        }
        String index = physicalIndex.trim();
        try {
            boolean exists = elasticsearchClient.indices().exists(r -> r.index(index)).value();
            if (exists) {
                return;
            }
            elasticsearchClient.indices().create(c -> c
                    .index(index)
                    .mappings(m -> m
                            .properties("id", p -> p.long_(l -> l))
                            .properties("sourceRevision", p -> p.keyword(k -> k))
                            .properties("searchable", p -> p.boolean_(b -> b))
                            .properties("title", p -> p.text(t -> t))
                            .properties("summary", p -> p.text(t -> t))
                            .properties("content", p -> p.text(t -> t))
                            .properties("categoryId", p -> p.long_(l -> l))
                            .properties("categoryName", p -> p.text(t -> t))
                            .properties("tagNames", p -> p.text(t -> t))
                            .properties("thumbnail", p -> p.keyword(k -> k))
                            .properties("viewCount", p -> p.long_(l -> l))
                            .properties("createTime", p -> p.long_(l -> l))
                    ));
        } catch (ElasticsearchException esEx) {
            String msg = esEx.getMessage() == null ? "" : esEx.getMessage();
            if (esEx.status() == 400 && msg.contains("resource_already_exists_exception")) {
                return;
            }
            throw new IllegalStateException("ES ensurePhysicalIndex failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            throw new IllegalStateException("ES ensurePhysicalIndex failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES ensurePhysicalIndex IO failure", ioEx);
        }
    }

    @Override
    public void refreshIndex(String physicalIndex) {
        try {
            elasticsearchClient.indices().refresh(r -> r.index(physicalIndex.trim()));
        } catch (ElasticsearchException esEx) {
            throw new IllegalStateException("ES refresh failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            throw new IllegalStateException("ES refresh failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES refresh IO failure", ioEx);
        }
    }

    @Override
    public long countDocumentsBySearchable(String physicalIndex, boolean searchable) {
        try {
            SearchResponse<JsonNode> resp = elasticsearchClient.search(req -> req
                    .index(physicalIndex.trim())
                    .size(0)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(q -> q.term(t -> t.field("searchable").value(searchable))), JsonNode.class);
            if (resp.hits() == null || resp.hits().total() == null) {
                return 0L;
            }
            return Math.max(0L, resp.hits().total().value());
        } catch (ElasticsearchException esEx) {
            throw new IllegalStateException("ES count failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            throw new IllegalStateException("ES count failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES count IO failure", ioEx);
        }
    }

    @Override
    public Map<String, SearchVisibleDoc> fetchSearchVisibleDocsByIds(String physicalIndex, List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        try {
            SearchResponse<JsonNode> resp = elasticsearchClient.search(req -> req
                    .index(physicalIndex.trim())
                    .size(docIds.size())
                    .version(true)
                    .query(q -> q.ids(ids -> ids.values(docIds))), JsonNode.class);
            Map<String, SearchVisibleDoc> result = new LinkedHashMap<>();
            if (resp.hits() != null && resp.hits().hits() != null) {
                for (Hit<JsonNode> hit : resp.hits().hits()) {
                    if (hit != null && hit.id() != null) {
                        result.put(hit.id(), new SearchVisibleDoc(hit.id(), hit.version(), hit.source()));
                    }
                }
            }
            return result;
        } catch (ElasticsearchException esEx) {
            throw new IllegalStateException("ES sample search failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            throw new IllegalStateException("ES sample search failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES sample search IO failure", ioEx);
        }
    }

    @Override
    public Set<String> getAliasTargets(String aliasName) {
        if (!StringUtils.hasText(aliasName)) {
            throw new IllegalArgumentException("aliasName must be non-blank");
        }
        String alias = aliasName.trim();
        try {
            GetAliasResponse response = elasticsearchClient.indices().getAlias(req -> req.name(alias));
            if (response == null || response.aliases() == null) {
                return Set.of();
            }
            return new LinkedHashSet<>(response.aliases().keySet());
        } catch (ElasticsearchException esEx) {
            if (esEx.status() == 404) {
                return Set.of();
            }
            throw new IllegalStateException("ES getAlias failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            if (status == 404) {
                return Set.of();
            }
            throw new IllegalStateException("ES getAlias failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES getAlias IO failure", ioEx);
        }
    }

    @Override
    public void switchAliasAtomically(String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex) {
        if (!StringUtils.hasText(aliasName) || !StringUtils.hasText(targetPhysicalIndex)) {
            throw new IllegalArgumentException("aliasName and targetPhysicalIndex must be non-blank");
        }
        String alias = aliasName.trim();
        String target = targetPhysicalIndex.trim();
        List<Action> actions = new ArrayList<>();
        if (removeFromIndices != null) {
            for (String oldIndex : removeFromIndices) {
                if (StringUtils.hasText(oldIndex) && !oldIndex.trim().equals(target)) {
                    String trimmedOld = oldIndex.trim();
                    actions.add(Action.of(a -> a.remove(r -> r.index(trimmedOld).alias(alias))));
                }
            }
        }
        actions.add(Action.of(a -> a.add(add -> add.index(target).alias(alias))));
        try {
            elasticsearchClient.indices().updateAliases(
                    UpdateAliasesRequest.of(u -> u.actions(actions)));
        } catch (ElasticsearchException esEx) {
            throw new IllegalStateException("ES atomic alias switch failed HTTP " + esEx.status(), esEx);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            throw new IllegalStateException("ES atomic alias switch failed HTTP " + status, respEx);
        } catch (IOException ioEx) {
            throw new IllegalStateException("ES atomic alias switch IO failure", ioEx);
        }
    }
}
