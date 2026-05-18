package org.example.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条 arXiv 论文或 GitHub 仓库摘要。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResearchFeedItemDto {

    private String source;
    private String title;
    private String url;
    private String summary;
    private String publishedAt;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
    }
}
