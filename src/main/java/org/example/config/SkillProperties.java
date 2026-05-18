package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 项目内 Skills：目录下 Markdown 合并后写入主对话系统提示（非 Cursor IDE Skill）。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "app.skills")
public class SkillProperties {

    /**
     * 是否将 Skills 拼入系统提示。
     */
    private boolean enabled = true;

    /**
     * 扫描目录（相对路径相对进程工作目录）；仅加载根目录下 *.md，按文件名排序。
     */
    private String directory = "./data/agent-skills";

    /**
     * 合并后的 Skills 正文最大字符数（含文件头尾说明），防止撑爆上下文。
     */
    private int maxChars = 8000;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = Math.max(512, maxChars);
    }
}
