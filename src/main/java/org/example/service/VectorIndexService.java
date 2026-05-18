package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.config.FileUploadConfig;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.util.MilvusExprEscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private DocumentTextExtractionService textExtractionService;

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 递归获取所有支持的文件（与 file.upload.allowed-extensions 一致），含子目录如 aiops-docs/group/
            List<File> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dirPath)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> isExtensionAllowedForIndexing(p.getFileName().toString()))
                        .forEach(p -> files.add(p.toFile()));
            } catch (IOException e) {
                throw new IllegalStateException("遍历知识库目录失败: " + targetPath, e);
            }

            if (files.isEmpty()) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.size());
            logger.info("开始索引目录（含子目录）: {}, 找到 {} 个文件", targetPath, files.size());

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath(), null);
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件（全局知识：运维文档、未带会话的上传）
     */
    public void indexSingleFile(String filePath) throws Exception {
        indexSingleFile(filePath, null);
    }

    /**
     * 索引单个文件
     *
     * @param filePath        文件路径
     * @param uploadSessionId 非空时表示本会话私有上传；null 表示全局文档（如 aiops-docs 启动索引、未传 session 的上传）
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath, String uploadSessionId) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}, uploadSessionId={}", path, uploadSessionId == null ? "(global)" : uploadSessionId);

        // 1. 读取文件内容（纯文本 / DOCX / PDF）
        String content = textExtractionService.extractPlainText(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content == null ? 0 : content.length());

        // 正文为空时仍建可检索占位（扫描版 PDF 等），避免「上传成功但知识库里查不到编号」
        if (content == null || content.isBlank()) {
            content = buildFallbackWhenExtractionEmpty(path);
            logger.warn("文件未抽取到正文，使用占位文本参与分片: {}", path.getFileName());
        }

        // 每个分片向量前带上文件名/编号，便于「按文档号提问」命中（如 AAS-CN-2026-0083）
        String contentForChunking = prependKnowledgeSourceHeader(path, content);

        // 2. 删除该文件在本作用域下的旧数据（如果存在）
        deleteExistingData(path.toString(), uploadSessionId);

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(contentForChunking, path.toString());
        if (chunks.isEmpty()) {
            DocumentChunk single = new DocumentChunk(contentForChunking, 0, contentForChunking.length(), 0);
            chunks = new ArrayList<>();
            chunks.add(single);
            logger.warn("分片结果为空，使用单条全文作为分片: {}", path.getFileName());
        }
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并插入 Milvus
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            
            try {
                // 生成向量（与入库正文一致，含来源头）
                String indexedText = chunk.getContent();
                if (indexedText != null && indexedText.length() > MilvusConstants.CONTENT_MAX_LENGTH) {
                    logger.warn("分片正文超过 Milvus VarChar 上限 {}，已截断: {}",
                            MilvusConstants.CONTENT_MAX_LENGTH, path.getFileName());
                    indexedText = indexedText.substring(0, MilvusConstants.CONTENT_MAX_LENGTH);
                }
                List<Float> vector = embeddingService.generateEmbedding(indexedText);

                // 构建元数据（包含文件信息）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size(), uploadSessionId);

                // 插入到 Milvus
                insertToMilvus(indexedText, vector, metadata, chunk.getChunkIndex());
                
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    private static String fileStem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 抽取失败时的占位正文，保证至少能按文件名/编号被向量检索到。
     */
    private static String buildFallbackWhenExtractionEmpty(Path path) {
        String fn = path.getFileName().toString();
        String stem = fileStem(path);
        return "[文件名: " + fn + "]\n[文档标识: " + stem + "]\n"
                + "该文件未能解析出正文（常见于扫描版 PDF、纯图片或加密文档）。"
                + "若需问答内容，请上传可抽取文本的 PDF/Word，或对扫描件做 OCR 后再上传。\n";
    }

    /**
     * 在全文前加上知识库来源头，使每个分片都携带文件名与无扩展名编号，改善「按编号/文件名提问」的召回。
     */
    private static String prependKnowledgeSourceHeader(Path path, String body) {
        String fn = path.getFileName().toString();
        String stem = fileStem(path);
        return "[知识库文档: " + fn + "]\n[文档标识: " + stem + "]\n\n" + body;
    }

    /**
     * 删除文件的旧数据：全局索引按 _source 整路径清理；会话上传仅删除该会话下该路径的向量。
     */
    private void deleteExistingData(String filePath, String uploadSessionId) {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            String escPath = MilvusExprEscape.stringLiteral(normalizedPath);

            String expr;
            if (uploadSessionId != null && !uploadSessionId.isBlank()) {
                String escSid = MilvusExprEscape.stringLiteral(uploadSessionId);
                expr = String.format("metadata[\"_source\"] == \"%s\" && metadata[\"_session_id\"] == \"%s\"",
                        escPath, escSid);
            } else {
                // 全局重建：只删 global / 旧无标量数据，勿删 _kb_scope=session（否则会话上传再被启动索引抹掉隔离）
                expr = String.format(
                        "(metadata[\"_source\"] == \"%s\") && (metadata[\"_kb_scope\"] == \"global\" || metadata[\"_kb_scope\"] is null)",
                        escPath);
            }
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            // 确保 collection 已加载（删除操作需要集合已加载）
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            // 状态码 65535 表示集合已经加载，这不是错误
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);

            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息与知识库可见范围）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks, String uploadSessionId) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);

        if (uploadSessionId != null && !uploadSessionId.isBlank()) {
            metadata.put("_kb_scope", "session");
            metadata.put("_session_id", uploadSessionId);
        } else {
            metadata.put("_kb_scope", "global");
        }
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertToMilvus(String content, List<Float> vector, 
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        try {
            // 确保 collection 已加载
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .build()
            );

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
            }

            // 生成唯一 ID（_source + 分片索引 + 可选会话，避免不同会话同名文件主键冲突）
            String source = (String) metadata.get("_source");
            String idKey = source + "_" + chunkIndex;
            Object sid = metadata.get("_session_id");
            if (sid != null && !sid.toString().isBlank()) {
                idKey = idKey + "_" + sid;
            }
            String id = UUID.nameUUIDFromBytes(idKey.getBytes(StandardCharsets.UTF_8)).toString();

            // 构建字段数据
            List<InsertParam.Field> fields = new ArrayList<>();
            
            // ID 字段
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            
            // content 字段
            fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
            
            // vector 字段
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            
            // metadata 字段（JSON 对象）
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

            // 构建插入参数
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            // 执行插入
            R<MutationResult> insertResponse = milvusClient.insert(insertParam);

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    private boolean isExtensionAllowedForIndexing(String filename) {
        String ext = fileExtension(filename);
        if (ext.isEmpty()) {
            return false;
        }
        String allowed = fileUploadConfig.getAllowedExtensions();
        if (allowed == null || allowed.isBlank()) {
            return false;
        }
        for (String token : allowed.split(",")) {
            if (ext.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String fileExtension(String filename) {
        int i = filename.lastIndexOf('.');
        if (i < 0 || i >= filename.length() - 1) {
            return "";
        }
        return filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
