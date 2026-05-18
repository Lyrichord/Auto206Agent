package org.example.skill;

import org.example.config.SkillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从配置目录加载根级 *.md 文件，合并为一段供 {@link org.example.service.ChatService} 写入系统提示的扩展说明。
 * <p>
 * 约定：按文件名排序合并；跳过 {@code README.md}；子目录不递归。
 */
@Service
public class ProjectSkillLoader {

    private static final Logger logger = LoggerFactory.getLogger(ProjectSkillLoader.class);

    private final SkillProperties skillProperties;

    private volatile String cachedPromptSection = "";

    public ProjectSkillLoader(SkillProperties skillProperties) {
        this.skillProperties = skillProperties;
    }

    @PostConstruct
    public void reload() {
        if (!skillProperties.isEnabled()) {
            cachedPromptSection = "";
            logger.info("app.skills.enabled=false，不加载项目 Skills");
            return;
        }
        Path dir = Paths.get(skillProperties.getDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            cachedPromptSection = "";
            logger.info("Skills 目录不存在，跳过: {}（可在该路径放置 .md 扩展提示）", dir);
            return;
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> !"readme.md".equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            logger.warn("列出 Skills 目录失败: {}", e.getMessage());
            cachedPromptSection = "";
            return;
        }
        if (mdFiles.isEmpty()) {
            cachedPromptSection = "";
            logger.debug("Skills 目录下无可用 .md 文件: {}", dir);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- 项目 Skills（Markdown 扩展，按文件名排序；与知识库正文独立）---\n");
        int appended = 0;
        for (Path p : mdFiles) {
            String name = p.getFileName().toString();
            try {
                String body = Files.readString(p, StandardCharsets.UTF_8).strip();
                if (body.isEmpty()) {
                    continue;
                }
                sb.append("\n### ").append(name).append("\n");
                sb.append(body).append('\n');
                appended++;
            } catch (IOException e) {
                logger.warn("读取 Skill 文件失败 {}: {}", name, e.getMessage());
            }
        }
        if (appended == 0) {
            cachedPromptSection = "";
            logger.debug("Skills 目录下 .md 均为空，不注入: {}", dir);
            return;
        }
        sb.append("--- Skills 结束 ---\n\n");
        String combined = sb.toString();
        int cap = skillProperties.getMaxChars();
        if (combined.length() > cap) {
            combined = combined.substring(0, cap) + "\n…（Skills 总长度超过 app.skills.max-chars 已截断）\n";
        }
        cachedPromptSection = combined;
        logger.info("已加载项目 Skills: {} 个 .md 文件，注入长度约 {}", mdFiles.size(), cachedPromptSection.length());
    }

    /**
     * 供系统提示拼接；未启用或无文件时返回空串。
     */
    public String getSkillsPromptSection() {
        return cachedPromptSection == null ? "" : cachedPromptSection;
    }
}
