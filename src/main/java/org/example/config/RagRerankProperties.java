package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 重排序（百炼 text-rerank）配置。
 */
@ConfigurationProperties(prefix = "rag.rerank")
public class RagRerankProperties {

    /**
     * 是否启用重排序；关闭时行为与原先一致（仅向量 TopK）。
     */
    private boolean enabled = true;

    /**
     * 重排序模型：qwen3-rerank（compatible-api）或 gte-rerank-v2（原生 text-rerank）。
     */
    private String model = "qwen3-rerank";

    /**
     * 向量召回数量 = min(maxCandidates, topK * candidateMultiplier)。
     */
    private int candidateMultiplier = 4;

    private int maxCandidates = 48;

    /**
     * 单条送入 rerank 的文本最大字符数（避免超长触发截断）。
     */
    private int maxCharsPerDocument = 8000;

    /**
     * 仅 qwen3-rerank / qwen3-vl-rerank 生效；gte-rerank-v2 忽略。
     */
    private String instruct = "Given a web search query, retrieve relevant passages that answer the query.";

    private String nativeUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private String compatibleUrl = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getCandidateMultiplier() {
        return candidateMultiplier;
    }

    public void setCandidateMultiplier(int candidateMultiplier) {
        this.candidateMultiplier = candidateMultiplier;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getMaxCharsPerDocument() {
        return maxCharsPerDocument;
    }

    public void setMaxCharsPerDocument(int maxCharsPerDocument) {
        this.maxCharsPerDocument = maxCharsPerDocument;
    }

    public String getInstruct() {
        return instruct;
    }

    public void setInstruct(String instruct) {
        this.instruct = instruct;
    }

    public String getNativeUrl() {
        return nativeUrl;
    }

    public void setNativeUrl(String nativeUrl) {
        this.nativeUrl = nativeUrl;
    }

    public String getCompatibleUrl() {
        return compatibleUrl;
    }

    public void setCompatibleUrl(String compatibleUrl) {
        this.compatibleUrl = compatibleUrl;
    }

    public int recallSize(int finalTopK) {
        if (!enabled) {
            return finalTopK;
        }
        int mult = Math.max(1, candidateMultiplier);
        return Math.min(maxCandidates, finalTopK * mult);
    }

    public boolean useQwen3CompatibleApi() {
        String m = model == null ? "" : model.toLowerCase();
        return m.startsWith("qwen3-rerank") && !m.contains("vl");
    }
}
