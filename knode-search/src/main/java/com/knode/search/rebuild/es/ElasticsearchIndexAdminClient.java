package com.knode.search.rebuild.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "knode.search.rebuild", name = "enabled", havingValue = "true")
public class ElasticsearchIndexAdminClient implements SearchIndexAdminClient {
    private static final List<String> ACTION_RESULT_ARRAYS = List.of("items", "actions", "results");

    private final ElasticsearchClient elasticsearchClient;
    private final ObjectMapper objectMapper;

    public ElasticsearchIndexAdminClient(ElasticsearchClient elasticsearchClient) {
        this(elasticsearchClient, new ObjectMapper());
    }

    @Autowired
    public ElasticsearchIndexAdminClient(ElasticsearchClient elasticsearchClient, ObjectMapper objectMapper) {
        this.elasticsearchClient = elasticsearchClient;
        this.objectMapper = objectMapper;
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
    public AliasSwitchResult switchAliasAtomically(
            String aliasName, Set<String> removeFromIndices, String targetPhysicalIndex) {
        if (!StringUtils.hasText(aliasName) || !StringUtils.hasText(targetPhysicalIndex)) {
            throw new IllegalArgumentException("aliasName and targetPhysicalIndex must be non-blank");
        }
        String alias = aliasName.trim();
        String target = targetPhysicalIndex.trim();
        List<Map<String, Object>> actions = new ArrayList<>();
        if (removeFromIndices != null) {
            for (String oldIndex : removeFromIndices) {
                if (StringUtils.hasText(oldIndex) && !oldIndex.trim().equals(target)) {
                    actions.add(removeActionWithMustExist(oldIndex.trim(), alias));
                }
            }
        }
        actions.add(addAction(target, alias));
        return executeAliasActions(actions);
    }

    @Override
    public AliasSwitchResult removeAliasBindingsAtomically(String aliasName, Set<String> removeFromIndices) {
        if (!StringUtils.hasText(aliasName)) {
            throw new IllegalArgumentException("aliasName must be non-blank");
        }
        String alias = aliasName.trim();
        List<Map<String, Object>> actions = new ArrayList<>();
        if (removeFromIndices != null) {
            for (String oldIndex : removeFromIndices) {
                if (StringUtils.hasText(oldIndex)) {
                    actions.add(removeActionWithMustExist(oldIndex.trim(), alias));
                }
            }
        }
        if (actions.isEmpty()) {
            return AliasSwitchResult.acknowledged();
        }
        return executeAliasActions(actions);
    }

    private AliasSwitchResult executeAliasActions(List<Map<String, Object>> actions) {
        if (!(elasticsearchClient._transport() instanceof Rest5ClientTransport restTransport)) {
            throw new IllegalStateException("ElasticsearchClient transport must be Rest5ClientTransport");
        }
        Rest5Client restClient = restTransport.restClient();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of("actions", actions));
        } catch (Exception serEx) {
            throw new IllegalStateException("Failed to serialize ES _aliases request", serEx);
        }

        Request request = new Request("POST", "/_aliases");
        request.setJsonEntity(payloadJson);
        try {
            Response response = restClient.performRequest(request);
            int status = response.getStatusCode();
            String body = readResponseBody(response);
            return inspectAliasResponse(status, body);
        } catch (ResponseException respEx) {
            int status = respEx.getResponse() == null ? 0 : respEx.getResponse().getStatusCode();
            String detail = "ES_ALIASES_HTTP_" + status + ":" + safeMessage(respEx);
            // Only synchronous HTTP 4xx (excluding 408 Request Timeout) proves ES rejected the request
            // before applying it and cannot arrive late. 408 and 5xx remain uncertain/in-flight!
            if (status >= 400 && status < 500 && status != 408) {
                return AliasSwitchResult.confirmedPreSwitchRejected(status, detail);
            }
            return AliasSwitchResult.uncertainInFlight(status, detail);
        } catch (IOException ioEx) {
            return AliasSwitchResult.uncertainInFlight(0,
                    "ES_ALIASES_IO_" + ioEx.getClass().getSimpleName() + ":" + safeMessage(ioEx));
        } catch (Exception ex) {
            return AliasSwitchResult.uncertainInFlight(0,
                    "ES_ALIASES_ERROR_" + ex.getClass().getSimpleName() + ":" + safeMessage(ex));
        }
    }

    private AliasSwitchResult inspectAliasResponse(int status, String body) {
        if (status < 200 || status >= 300) {
            if (status >= 400 && status < 500 && status != 408) {
                return AliasSwitchResult.confirmedPreSwitchRejected(status, "ES_ALIASES_HTTP_" + status);
            }
            return AliasSwitchResult.uncertainInFlight(status, "ES_ALIASES_HTTP_" + status);
        }
        if (!StringUtils.hasText(body)) {
            return AliasSwitchResult.partialOrUnacknowledged(status, "ES_ALIASES_EMPTY_BODY");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception parseEx) {
            return AliasSwitchResult.partialOrUnacknowledged(status,
                    "ES_ALIASES_UNPARSEABLE_BODY:" + parseEx.getClass().getSimpleName());
        }
        if (root == null || !root.isObject()) {
            return AliasSwitchResult.partialOrUnacknowledged(status, "ES_ALIASES_NON_OBJECT_BODY");
        }
        if (root.has("error") && !root.get("error").isNull()) {
            return AliasSwitchResult.partialOrUnacknowledged(status,
                    "ES_ALIASES_BODY_ERROR:" + root.get("error").toString());
        }
        if (root.has("errors") && root.get("errors").asBoolean(false)) {
            return AliasSwitchResult.partialOrUnacknowledged(status, "ES_ALIASES_ERRORS_TRUE:" + truncate(body));
        }
        if (root.has("failures") && root.get("failures").isArray() && !root.get("failures").isEmpty()) {
            return AliasSwitchResult.partialOrUnacknowledged(status,
                    "ES_ALIASES_FAILURES_NON_EMPTY:" + root.get("failures").toString());
        }
        for (String arrayField : ACTION_RESULT_ARRAYS) {
            JsonNode arr = root.get(arrayField);
            if (arr != null && arr.isArray()) {
                String actionErr = findActionItemFailure(arr);
                if (actionErr != null) {
                    return AliasSwitchResult.partialOrUnacknowledged(status,
                            "ES_ALIASES_ACTION_FAILURE(" + arrayField + "):" + actionErr);
                }
            }
        }
        JsonNode ackNode = root.get("acknowledged");
        if (ackNode == null || !ackNode.isBoolean() || !ackNode.asBoolean()) {
            return AliasSwitchResult.partialOrUnacknowledged(status,
                    "ES_ALIASES_NOT_ACKNOWLEDGED:" + truncate(body));
        }
        return AliasSwitchResult.acknowledged();
    }

    private static String findActionItemFailure(JsonNode arrayNode) {
        for (JsonNode entry : arrayNode) {
            if (entry == null || entry.isNull()) {
                continue;
            }
            String direct = inspectActionObject(entry);
            if (direct != null) {
                return direct;
            }
            if (entry.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    JsonNode nested = field.getValue();
                    if (nested != null && nested.isObject()) {
                        String nestedErr = inspectActionObject(nested);
                        if (nestedErr != null) {
                            return field.getKey() + ":" + nestedErr;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String inspectActionObject(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        if (node.has("error") && !node.get("error").isNull()) {
            return "error=" + node.get("error").toString();
        }
        if (node.has("acknowledged") && node.get("acknowledged").isBoolean() && !node.get("acknowledged").asBoolean()) {
            return "acknowledged=false";
        }
        if (node.has("failed") && node.get("failed").asBoolean(false)) {
            return "failed=true";
        }
        if (node.has("status") && node.get("status").isInt() && node.get("status").asInt() >= 300) {
            return "status=" + node.get("status").asInt();
        }
        return null;
    }

    private static Map<String, Object> removeActionWithMustExist(String index, String alias) {
        Map<String, Object> removeBody = new LinkedHashMap<>();
        removeBody.put("index", index);
        removeBody.put("alias", alias);
        removeBody.put("must_exist", true);
        return Map.of("remove", removeBody);
    }

    private static Map<String, Object> addAction(String index, String alias) {
        Map<String, Object> addBody = new LinkedHashMap<>();
        addBody.put("index", index);
        addBody.put("alias", alias);
        return Map.of("add", addBody);
    }

    private static String readResponseBody(Response response) throws IOException {
        if (response.getEntity() == null) {
            return "";
        }
        try (InputStream in = response.getEntity().getContent()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        return msg == null ? ex.getClass().getSimpleName() : truncate(msg);
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= 240 ? raw : raw.substring(0, 240);
    }
}
