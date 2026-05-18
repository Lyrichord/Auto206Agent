package org.example.config;

import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时将配置的目录（含 {@code aiops-docs}）写入向量库，避免仅依赖 {@code make upload} 时才能 RAG。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class KnowledgeIndexBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIndexBootstrap.class);

    @Autowired
    private KnowledgeProperties knowledgeProperties;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Override
    public void run(ApplicationArguments args) {
        if (!knowledgeProperties.isBootstrapIndexOnStartup()) {
            logger.info("knowledge.bootstrap-index-on-startup=false，跳过启动时知识库目录索引");
            return;
        }
        List<String> dirs = new ArrayList<>(knowledgeProperties.getIndexedDirectories());
        String mirror = knowledgeProperties.getMirrorUploadTo();
        if (mirror == null || mirror.isBlank()) {
            String uploadDir = fileUploadConfig.getPath();
            if (uploadDir != null && !uploadDir.isBlank()) {
                Path up = resolvePath(uploadDir.trim());
                boolean already = dirs.stream().anyMatch(d -> resolvePath(d.trim()).equals(up));
                if (!already) {
                    dirs.add(uploadDir.trim());
                }
            }
        }
        Set<Path> seen = new LinkedHashSet<>();
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path p = resolvePath(dir.trim());
            if (!seen.add(p)) {
                continue;
            }
            if (!Files.isDirectory(p)) {
                logger.warn("知识库目录不存在或不是目录，跳过索引: {}", p);
                continue;
            }
            try {
                logger.info("启动时索引知识库目录: {}", p);
                vectorIndexService.indexDirectory(p.toString());
            } catch (Exception e) {
                logger.warn("启动时索引知识库目录失败（Milvus 未就绪或目录异常时可忽略）: {} — {}", p, e.getMessage());
            }
        }
    }

    private static Path resolvePath(String dir) {
        Path p = Paths.get(dir).normalize();
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().resolve(p).normalize();
        }
        return p;
    }
}
