package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话持久化目录（相对路径相对进程工作目录）。
 */
@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryProperties {

    /**
     * 每个会话一个 JSON 文件的存储目录。
     */
    private String directory = "./data/chat-memory";

    /**
     * 进入模型系统提示「对话历史」的最近轮数（1 轮 = 1 条用户 + 1 条助手）。
     * 过大会拉长 system prompt、增加延迟与费用；建议 10～30。
     */
    private int maxMessagePairs = 20;

    /**
     * 是否启用「摘要记忆」：当原始轮数超过 {@link #summaryRecentRawPairs} 且超出部分达到
     * {@link #summaryCompressEveryNPairs} 时，将最旧若干轮压缩为一段会话摘要，模型侧仅带摘要 + 最近 K 轮原文。
     * 默认 false，避免改变既有部署行为；需压缩时在 application.yml 中设为 true。
     */
    private boolean summaryCompressionEnabled = false;

    /**
     * 摘要后保留的完整原文轮数（最近 K 对 user+assistant）；应小于 {@link #maxMessagePairs} 才有压缩空间。
     */
    private int summaryRecentRawPairs = 8;

    /**
     * 每积累满 N 对「超出 K 的最早对话」即调用一次模型合并进会话摘要（单次归档 N 对）。
     */
    private int summaryCompressEveryNPairs = 6;

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public int getMaxMessagePairs() {
        return maxMessagePairs;
    }

    public void setMaxMessagePairs(int maxMessagePairs) {
        this.maxMessagePairs = maxMessagePairs;
    }

    public boolean isSummaryCompressionEnabled() {
        return summaryCompressionEnabled;
    }

    public void setSummaryCompressionEnabled(boolean summaryCompressionEnabled) {
        this.summaryCompressionEnabled = summaryCompressionEnabled;
    }

    public int getSummaryRecentRawPairs() {
        return summaryRecentRawPairs;
    }

    public void setSummaryRecentRawPairs(int summaryRecentRawPairs) {
        this.summaryRecentRawPairs = Math.max(1, summaryRecentRawPairs);
    }

    public int getSummaryCompressEveryNPairs() {
        return summaryCompressEveryNPairs;
    }

    public void setSummaryCompressEveryNPairs(int summaryCompressEveryNPairs) {
        this.summaryCompressEveryNPairs = Math.max(1, summaryCompressEveryNPairs);
    }
}
