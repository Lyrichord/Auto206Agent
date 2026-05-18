package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * arXiv + GitHub 文献/仓库聚合（最近一周自动驾驶相关）。
 */
@ConfigurationProperties(prefix = "research-feed")
public class ResearchFeedProperties {

    /** 可选 GitHub PAT，提高 Search API 限额（未配置时 60 次/小时/IP） */
    private String githubToken = "";

    private int connectTimeoutMs = 15000;

    private int readTimeoutMs = 30000;

    /** 会话在内存中保留时间（毫秒），超时后「再来10篇」需重新获取 */
    private long sessionTtlMs = 30 * 60 * 1000L;

    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String githubToken) {
        this.githubToken = githubToken != null ? githubToken.trim() : "";
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getSessionTtlMs() {
        return sessionTtlMs;
    }

    public void setSessionTtlMs(long sessionTtlMs) {
        this.sessionTtlMs = sessionTtlMs;
    }
}
