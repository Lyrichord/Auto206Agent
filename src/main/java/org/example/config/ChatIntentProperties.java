package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 轻量级规则意图识别：写入系统提示并可选跳过部分问题的 RAG 预检索。
 */
@ConfigurationProperties(prefix = "app.chat.intent")
public class ChatIntentProperties {

    /**
     * 是否启用意图识别（关闭时不追加【意图】提示、不跳过预检索）。
     */
    private boolean enabled = true;

    /**
     * 对「明显只需专用工具、无需知识库」的意图跳过向量预检索，降低延迟与噪声。
     */
    private boolean skipPrefetchForToolFirstIntents = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSkipPrefetchForToolFirstIntents() {
        return skipPrefetchForToolFirstIntents;
    }

    public void setSkipPrefetchForToolFirstIntents(boolean skipPrefetchForToolFirstIntents) {
        this.skipPrefetchForToolFirstIntents = skipPrefetchForToolFirstIntents;
    }
}
