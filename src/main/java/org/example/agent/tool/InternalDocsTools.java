package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.RagRerankProperties;
import org.example.context.RagRequestContext;
import org.example.service.RagRerankService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    
    private final VectorSearchService vectorSearchService;

    private final RagRerankService ragRerankService;

    private final RagRerankProperties ragRerankProperties;

    private final int topK;

    private final int toolFragmentMaxChars;

    /**
     * 非 null 时：工具内 Milvus 过滤固定使用该会话（解决流式/ReactAgent 在 Reactor 线程执行工具时
     * {@link RagRequestContext} ThreadLocal 不可用、退化为全库检索导致跨会话泄漏）。
     */
    @Nullable
    private final String boundRagSessionId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InternalDocsTools(
            VectorSearchService vectorSearchService,
            RagRerankService ragRerankService,
            RagRerankProperties ragRerankProperties,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.tool-fragment-max-chars:24000}") int toolFragmentMaxChars) {
        this(vectorSearchService, ragRerankService, ragRerankProperties, topK, toolFragmentMaxChars, null);
    }

    private InternalDocsTools(
            VectorSearchService vectorSearchService,
            RagRerankService ragRerankService,
            RagRerankProperties ragRerankProperties,
            int topK,
            int toolFragmentMaxChars,
            @Nullable String boundRagSessionId) {
        this.vectorSearchService = vectorSearchService;
        this.ragRerankService = ragRerankService;
        this.ragRerankProperties = ragRerankProperties;
        this.topK = topK;
        this.toolFragmentMaxChars = toolFragmentMaxChars;
        this.boundRagSessionId = boundRagSessionId;
    }

    /**
     * 绑定当前 HTTP 对话会话 ID 的副本，供 {@link org.example.service.ChatService#createReactAgent} 每请求注入。
     */
    public InternalDocsTools scopedToChatSession(@Nullable String chatSessionId) {
        return new InternalDocsTools(vectorSearchService, ragRerankService, ragRerankProperties,
                topK, toolFragmentMaxChars, chatSessionId);
    }

    private String resolveSessionIdForFilter() {
        if (boundRagSessionId != null && !boundRagSessionId.isBlank()) {
            return boundRagSessionId;
        }
        return RagRequestContext.getSessionId();
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Search the vector knowledge base (Milvus). Returns JSON: status + fragments[].body (main document text), score, metadata. " +
            "Always base answers on fragments[].body content. " +
            "If the user message already starts with 【背景摘录】, you may skip unless you need another query. " +
            "When the user uses pronouns (他/她/此人) for a person already named in the conversation, resolve to that full name in the search query—never search with the bare pronoun alone. " +
            "For roster / 「某人是谁」questions: only state membership if the person's full name literally appears in fragments[].body in roster context; if absent, say not in KB—never invent lab members. " +
            "Use for lab roster/publications/projects (aiops-docs/group), server CPU/GPU playbooks, and session-uploaded PDF/DOCX/TXT.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        return retrieveInternalDocsJson(query, resolveSessionIdForFilter());
    }

    /**
     * 供 {@link org.example.service.ChatService} 预检索使用：显式传入会话 ID，不依赖 ThreadLocal（流式对话线程与 Reactor 线程不一致）。
     */
    public String retrieveInternalDocsJsonForPrefetch(String query, String chatSessionId) {
        return retrieveInternalDocsJson(query, chatSessionId);
    }

    private String retrieveInternalDocsJson(String query, String sessionIdForFilter) {
        try {
            int recallK = ragRerankProperties.recallSize(topK);
            List<VectorSearchService.SearchResult> candidates =
                    vectorSearchService.searchSimilarDocumentsMerged(query, recallK, sessionIdForFilter);
            List<VectorSearchService.SearchResult> searchResults =
                    ragRerankService.rerankIfEnabled(query, candidates, topK);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            int totalChars = 0;
            for (VectorSearchService.SearchResult r : searchResults) {
                if (r.getContent() != null) {
                    totalChars += r.getContent().length();
                }
            }
            logger.info("queryInternalDocs 命中 {} 条，正文合计约 {} 字符（写入工具 JSON 前）", searchResults.size(), totalChars);

            return buildToolResponseJson(searchResults);

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }

    /**
     * 将检索结果格式化为带 {@code fragments[].body} 的 JSON，便于模型直接阅读正文（避免仅扫过 Lombok 序列化字段名）。
     */
    private String buildToolResponseJson(List<VectorSearchService.SearchResult> searchResults) throws JsonProcessingException {
        int cap = Math.max(2000, toolFragmentMaxChars);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("status", "ok");
        root.put("hint", "请主要依据 fragments 数组中每条 body 的文本回答用户；metadata 含文件名与路径。");
        ArrayNode fragments = root.putArray("fragments");
        int rank = 0;
        for (VectorSearchService.SearchResult r : searchResults) {
            ObjectNode f = fragments.addObject();
            f.put("rank", ++rank);
            f.put("score", r.getScore());
            String body = r.getContent() != null ? r.getContent() : "";
            if (body.length() > cap) {
                int orig = body.length();
                body = body.substring(0, cap) + "\n...[本条已截断，原始长度 " + orig + " 字符]";
            }
            f.put("body", body);
            if (r.getMetadata() != null) {
                f.put("metadata", r.getMetadata());
            }
        }
        return objectMapper.writeValueAsString(root);
    }
}
