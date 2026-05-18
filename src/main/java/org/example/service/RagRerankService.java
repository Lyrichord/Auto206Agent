package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.RagRerankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用阿里云百炼文本重排序 API，对向量召回结果二次排序。
 */
@Service
public class RagRerankService {

    private static final Logger logger = LoggerFactory.getLogger(RagRerankService.class);

    private final RagRerankProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;

    public RagRerankService(
            RagRerankProperties properties,
            ObjectMapper objectMapper,
            @Value("${dashscope.api.key}") String apiKey) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 若未启用或候选不足，则直接截断为前 {@code finalTopK} 条；否则调用远程 rerank。
     */
    public List<VectorSearchService.SearchResult> rerankIfEnabled(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        if (!properties.isEnabled() || finalTopK <= 0) {
            return firstN(candidates, finalTopK);
        }
        if (candidates.size() == 1) {
            return candidates;
        }
        try {
            List<String> texts = new ArrayList<>(candidates.size());
            for (VectorSearchService.SearchResult r : candidates) {
                texts.add(truncateDoc(r.getContent()));
            }
            int topN = Math.min(finalTopK, candidates.size());
            List<RerankHit> hits = callRerankApi(query, texts, topN);
            if (hits.isEmpty()) {
                logger.warn("Rerank API 未返回有效顺序，回退为向量排序");
                return firstN(candidates, finalTopK);
            }
            List<VectorSearchService.SearchResult> out = new ArrayList<>();
            for (RerankHit hit : hits) {
                int idx = hit.index();
                if (idx >= 0 && idx < candidates.size()) {
                    VectorSearchService.SearchResult one = candidates.get(idx);
                    one.setScore(hit.relevanceScore());
                    out.add(one);
                }
                if (out.size() >= finalTopK) {
                    break;
                }
            }
            return out.isEmpty() ? firstN(candidates, finalTopK) : out;
        } catch (Exception e) {
            logger.warn("Rerank 调用失败，使用向量排序: {}", e.getMessage());
            return firstN(candidates, finalTopK);
        }
    }

    private List<RerankHit> callRerankApi(String query, List<String> documents, int topN) throws Exception {
        String model = properties.getModel();
        if (model == null || model.isBlank()) {
            model = "qwen3-rerank";
        }
        String body;
        String url;
        if (properties.useQwen3CompatibleApi()) {
            url = properties.getCompatibleUrl();
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model.trim());
            req.put("query", query);
            req.put("documents", documents);
            req.put("top_n", topN);
            if (properties.getInstruct() != null && !properties.getInstruct().isBlank()) {
                req.put("instruct", properties.getInstruct());
            }
            body = objectMapper.writeValueAsString(req);
        } else {
            url = properties.getNativeUrl();
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model.trim());
            ObjectNode input = root.putObject("input");
            input.put("query", query);
            ArrayNode arr = input.putArray("documents");
            for (String d : documents) {
                arr.add(d);
            }
            ObjectNode parameters = root.putObject("parameters");
            parameters.put("return_documents", false);
            parameters.put("top_n", topN);
            body = objectMapper.writeValueAsString(root);
        }

        String responseBody = restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("empty rerank response");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.hasNonNull("code") && !root.path("code").asText().isBlank()) {
            throw new IllegalStateException(root.path("code").asText() + ": " + root.path("message").asText());
        }
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("results");
        }
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("data");
        }
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("no results array in rerank response");
        }

        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode item : results) {
            int index = -1;
            if (item.has("index")) {
                index = item.get("index").asInt(-1);
            } else if (item.has("id")) {
                index = item.get("id").asInt(-1);
            }
            if (index < 0) {
                continue;
            }
            float rel = (float) (item.has("relevance_score")
                    ? item.get("relevance_score").asDouble()
                    : item.path("score").asDouble(0.0));
            hits.add(new RerankHit(index, rel));
        }
        return hits;
    }

    private String truncateDoc(String content) {
        if (content == null) {
            return "";
        }
        int max = Math.max(500, properties.getMaxCharsPerDocument());
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max);
    }

    private static List<VectorSearchService.SearchResult> firstN(
            List<VectorSearchService.SearchResult> list, int n) {
        if (n >= list.size()) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>(list.subList(0, n));
    }

    private record RerankHit(int index, float relevanceScore) {}
}
