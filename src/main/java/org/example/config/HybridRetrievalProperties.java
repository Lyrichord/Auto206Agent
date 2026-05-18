package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 混合召回：向量 + 关键词（Milvus like 拉取）+ BM25 融合。
 */
@ConfigurationProperties(prefix = "rag.hybrid")
public class HybridRetrievalProperties {

    /** 是否启用混合召回（关闭则仅保留原向量多路合并逻辑） */
    private boolean enabled = true;

    /** 向量分支在合并子查询后的目标条数（略放大便于与关键词并集） */
    private int vectorBranchTarget = 64;

    /** 每个关键词 Milvus query 条数上限 */
    private int lexicalLimitPerTerm = 14;

    /** 参与 like 检索的关键词个数上限 */
    private int maxLexicalTerms = 8;

    /** 关键词最短字符数（中英混合时过滤单字母噪声） */
    private int minTermLength = 2;

    /**
     * 融合时向量相似度权重（0~1），BM25 权重为 {@code 1 - vectorWeight}。
     * 向量分支为 L2 距离转相似度后再与 BM25 分数做 min-max 归一化加权。
     */
    private double vectorWeight = 0.55;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVectorBranchTarget() {
        return vectorBranchTarget;
    }

    public void setVectorBranchTarget(int vectorBranchTarget) {
        this.vectorBranchTarget = Math.max(8, vectorBranchTarget);
    }

    public int getLexicalLimitPerTerm() {
        return lexicalLimitPerTerm;
    }

    public void setLexicalLimitPerTerm(int lexicalLimitPerTerm) {
        this.lexicalLimitPerTerm = Math.max(1, lexicalLimitPerTerm);
    }

    public int getMaxLexicalTerms() {
        return maxLexicalTerms;
    }

    public void setMaxLexicalTerms(int maxLexicalTerms) {
        this.maxLexicalTerms = Math.max(1, maxLexicalTerms);
    }

    public int getMinTermLength() {
        return minTermLength;
    }

    public void setMinTermLength(int minTermLength) {
        this.minTermLength = Math.max(1, minTermLength);
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        if (vectorWeight < 0) {
            vectorWeight = 0;
        } else if (vectorWeight > 1) {
            vectorWeight = 1;
        }
        this.vectorWeight = vectorWeight;
    }
}
