package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.config.HybridRetrievalProperties;
import org.example.config.KnowledgeProperties;
import org.example.constant.MilvusConstants;
import org.example.context.RagRequestContext;
import org.example.service.lexical.Bm25Okapi;
import org.example.service.lexical.QueryLexicalTerms;
import org.example.util.MilvusExprEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 向量搜索服务：Milvus ANN 检索；在 {@link HybridRetrievalProperties#enabled} 时合并
 * 标量 {@code content like "%词%"} 拉取与 Okapi BM25 加权，再交给 {@link RagRerankService}。
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    /** 与 InternalDocsTools 一致：从文号类编号拆子查询 */
    private static final Pattern DOC_REFERENCE_PATTERN =
            Pattern.compile("\\b[A-Za-z]{2,}-[A-Za-z]{2,}-\\d{4}-\\d{4}\\b");

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private KnowledgeProperties knowledgeProperties;

    @Autowired
    private HybridRetrievalProperties hybridRetrievalProperties;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return searchSimilarDocuments(query, topK, null);
    }

    /**
     * @param explicitSessionId 非空时优先用于会话可见性过滤（流式预检索等无法依赖 ThreadLocal 的场景）
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK, @Nullable String explicitSessionId) {
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            // 1. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 2. 构建搜索参数（可选：按会话过滤，仅全局文档 + 本会话上传）
            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}");
            String visibilityExpr = buildSessionVisibilityExpr(explicitSessionId);
            if (visibilityExpr != null && !visibilityExpr.isBlank()) {
                searchBuilder.withExpr(visibilityExpr);
                logger.debug("向量检索附加会话可见性过滤: {}", visibilityExpr);
            }
            SearchParam searchParam = searchBuilder.build();

            // 3. 执行搜索（部分 Milvus 版本对 JSON 的 == null 等表达式不兼容，失败时去掉过滤重试一次）
            R<SearchResults> searchResponse = milvusClient.search(searchParam);
            if (searchResponse.getStatus() != 0 && visibilityExpr != null && !visibilityExpr.isBlank()) {
                // 禁止去掉会话过滤重试：否则会返回全库向量，造成跨会话上传内容泄漏
                logger.error("Milvus 带会话过滤检索失败 (status={}): {}，返回空结果",
                        searchResponse.getStatus(), searchResponse.getMessage());
                return List.of();
            }

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                
                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                
                results.add(result);
            }

            logger.info("搜索完成, 找到 {} 个相似文档", results.size());
            return results;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 本会话仅检索：全局运维文档（_kb_scope=global）+ 当前会话上传（_kb_scope=session 且 _session_id 匹配）。
     */
    private String buildSessionVisibilityExpr(@Nullable String explicitSessionId) {
        if (!knowledgeProperties.isSessionScopedSearch()) {
            return null;
        }
        String sid = explicitSessionId;
        if (sid == null || sid.isBlank()) {
            sid = RagRequestContext.getSessionId();
        }
        if (sid == null || sid.isBlank()) {
            return null;
        }
        String e = MilvusExprEscape.stringLiteral(sid);
        // 仅 global + 本会话 session；不再放行 _kb_scope is null（否则任意未打标分片会对全会话可见，造成跨会话泄漏）
        return "(metadata[\"_kb_scope\"] == \"global\") "
                + "or (metadata[\"_kb_scope\"] == \"session\" and metadata[\"_session_id\"] == \"" + e + "\")";
    }

    /**
     * 对整句问题 + 文号子串分别检索并去重（L2 距离越小越好），用于提升「按文档编号提问」的召回。
     */
    public List<SearchResult> searchSimilarDocumentsMerged(String query, int recallK) {
        return searchSimilarDocumentsMerged(query, recallK, null);
    }

    public List<SearchResult> searchSimilarDocumentsMerged(String query, int recallK, @Nullable String explicitSessionId) {
        if (!hybridRetrievalProperties.isEnabled()) {
            return vectorVariantsMerged(query, recallK, explicitSessionId);
        }
        return hybridVectorLexicalMerged(query, recallK, explicitSessionId);
    }

    /**
     * 原逻辑：多子查询向量召回 + 按 id 去重保留更小 L2 距离 + 截断。
     */
    private List<SearchResult> vectorVariantsMerged(String query, int recallK, @Nullable String explicitSessionId) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (query != null && !query.isBlank()) {
            variants.add(query.trim());
        }
        Matcher m = DOC_REFERENCE_PATTERN.matcher(query == null ? "" : query);
        while (m.find()) {
            variants.add(m.group());
        }
        Map<String, SearchResult> bestById = new LinkedHashMap<>();
        for (String q : variants) {
            List<SearchResult> part = searchSimilarDocuments(q, recallK, explicitSessionId);
            for (SearchResult r : part) {
                if (r.getId() == null) {
                    continue;
                }
                SearchResult existing = bestById.get(r.getId());
                if (existing == null || r.getScore() < existing.getScore()) {
                    bestById.put(r.getId(), r);
                }
            }
        }
        List<SearchResult> merged = new ArrayList<>(bestById.values());
        merged.sort(Comparator.comparingDouble(SearchResult::getScore));
        int cap = Math.max(recallK, recallK * Math.max(1, variants.size()));
        if (merged.size() > cap) {
            return new ArrayList<>(merged.subList(0, cap));
        }
        return merged;
    }

    /**
     * 混合召回：向量多路合并 + Milvus 标量 like 关键词拉取并集 + BM25 与向量相似度加权融合，供后续 rerank。
     */
    private List<SearchResult> hybridVectorLexicalMerged(String query, int recallK, @Nullable String explicitSessionId) {
        int vecRecall = Math.max(recallK, hybridRetrievalProperties.getVectorBranchTarget());
        List<SearchResult> vectorPart = vectorVariantsMerged(query, vecRecall, explicitSessionId);
        Map<String, SearchResult> byId = new LinkedHashMap<>();
        for (SearchResult r : vectorPart) {
            if (r.getId() != null) {
                byId.put(r.getId(), r);
            }
        }
        List<String> likeTerms = QueryLexicalTerms.extract(
                query,
                hybridRetrievalProperties.getMaxLexicalTerms(),
                hybridRetrievalProperties.getMinTermLength());
        for (String term : likeTerms) {
            for (SearchResult r : queryContentLike(term, hybridRetrievalProperties.getLexicalLimitPerTerm(), explicitSessionId)) {
                if (r.getId() == null) {
                    continue;
                }
                if (!byId.containsKey(r.getId())) {
                    byId.put(r.getId(), r);
                }
            }
        }
        List<SearchResult> union = new ArrayList<>(byId.values());
        if (union.isEmpty()) {
            return union;
        }
        List<String> bm25Terms = QueryLexicalTerms.extract(query, 24, hybridRetrievalProperties.getMinTermLength());
        List<String> texts = new ArrayList<>(union.size());
        for (SearchResult r : union) {
            texts.add(r.getContent() == null ? "" : r.getContent());
        }
        List<Double> bm25 = Bm25Okapi.scores(bm25Terms, texts);
        List<Double> vecSim = new ArrayList<>(union.size());
        for (SearchResult r : union) {
            float d = r.getScore();
            double sim = (d >= Float.MAX_VALUE / 4) ? 0.0 : 1.0 / (1.0 + d);
            vecSim.add(sim);
        }
        List<Double> nb = minMaxNorm(bm25);
        List<Double> nv = minMaxNorm(vecSim);
        double w = hybridRetrievalProperties.getVectorWeight();
        List<Double> fused = new ArrayList<>(union.size());
        for (int i = 0; i < union.size(); i++) {
            fused.add(w * nv.get(i) + (1.0 - w) * nb.get(i));
        }
        List<Integer> order = new ArrayList<>(union.size());
        for (int i = 0; i < union.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> fused.get(i)).reversed());
        int outCap = Math.min(union.size(), Math.max(recallK, recallK + likeTerms.size() * hybridRetrievalProperties.getLexicalLimitPerTerm()));
        outCap = Math.min(outCap, 120);
        List<SearchResult> out = new ArrayList<>();
        for (int k = 0; k < outCap && k < order.size(); k++) {
            SearchResult r = union.get(order.get(k));
            r.setScore(-fused.get(order.get(k)).floatValue());
            out.add(r);
        }
        out.sort(Comparator.comparingDouble(SearchResult::getScore));
        return out;
    }

    private List<SearchResult> queryContentLike(String term, long limit, @Nullable String explicitSessionId) {
        String like = contentLikeExpr(term);
        if (like == null) {
            return List.of();
        }
        String vis = buildSessionVisibilityExpr(explicitSessionId);
        String expr = vis == null || vis.isBlank() ? like : "(" + vis + ") and (" + like + ")";
        try {
            List<SearchResult> rows = runMilvusQuery(expr, limit);
            if (!rows.isEmpty()) {
                return rows;
            }
        } catch (Exception e) {
            logger.debug("Milvus 关键词 query 失败（将跳过该词）: {} — {}", term, e.getMessage());
        }
        return List.of();
    }

    private List<SearchResult> runMilvusQuery(String expr, long limit) {
        QueryParam param = QueryParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr(expr)
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .withLimit(limit)
                .build();
        R<QueryResults> resp = milvusClient.query(param);
        if (resp.getStatus() != 0) {
            throw new IllegalStateException(resp.getMessage());
        }
        QueryResultsWrapper w = new QueryResultsWrapper(resp.getData());
        List<SearchResult> list = new ArrayList<>();
        for (QueryResultsWrapper.RowRecord row : w.getRowRecords()) {
            SearchResult r = new SearchResult();
            r.setId(String.valueOf(row.get("id")));
            Object c = row.get("content");
            r.setContent(c == null ? "" : String.valueOf(c));
            r.setScore(Float.MAX_VALUE);
            Object meta = row.get("metadata");
            if (meta != null) {
                r.setMetadata(meta.toString());
            }
            list.add(r);
        }
        return list;
    }

    private static String contentLikeExpr(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String safe = term.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "\\%").replace("_", "\\_");
        return "content like \"%" + safe + "%\"";
    }

    private static List<Double> minMaxNorm(List<Double> values) {
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        List<Double> out = new ArrayList<>(values.size());
        if (max <= min + 1e-9) {
            for (int i = 0; i < values.size(); i++) {
                out.add(0.5);
            }
            return out;
        }
        for (Double v : values) {
            out.add((v - min) / (max - min));
        }
        return out;
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

    }
}
