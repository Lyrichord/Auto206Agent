package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.config.KnowledgeProperties;
import org.example.dto.FileUploadRes;
import org.example.service.ChatSessionPersistenceService;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @Autowired
    private KnowledgeProperties knowledgeProperties;

    @Autowired
    private ChatSessionPersistenceService chatSessionPersistenceService;

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 使用原始文件名（可为 curl 传入的相对路径如 group/x.md），以便与镜像目录结构一致
            Path relative = safeMirrorRelativePath(originalFilename);
            if (relative == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("非法文件名（不允许 .. 等）");
            }
            Path filePath = uploadDir.resolve(relative).normalize();
            if (!filePath.startsWith(uploadDir)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("非法文件路径");
            }
            Path uploadParent = filePath.getParent();
            if (uploadParent != null) {
                Files.createDirectories(uploadParent);
            }
            // 如果文件已存在，先删除旧文件（实现覆盖更新）
            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }
            
            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            String uploadSessionId = null;
            if (knowledgeProperties.isSessionScopedUploads()
                    && sessionId != null
                    && !sessionId.isBlank()) {
                uploadSessionId = sessionId.trim();
            }

            // 会话上传：只向量化 session-rag-store 下副本，避免镜像进 aiops-docs 后被启动全量索引打成 global 导致跨会话泄漏
            Path pathToIndex;
            if (uploadSessionId != null) {
                Path rel = safeMirrorRelativePath(originalFilename);
                if (rel == null) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("非法文件名（不允许 .. 等）");
                }
                pathToIndex = copyToSessionRagStore(uploadSessionId, rel, filePath);
                logger.info("会话上传：向量化隔离路径 {}", pathToIndex);
            } else {
                pathToIndex = filePath;
                Path mirrored = mirrorToKnowledgeDirIfConfigured(originalFilename, filePath);
                if (mirrored != null) {
                    pathToIndex = mirrored;
                }
            }

            // 文件上传成功后，自动调用向量索引服务（若启用镜像，仅对镜像路径建索引，避免与 uploads 双份向量）
            try {
                logger.info("开始为上传文件创建向量索引: {}, uploadSessionId={}", pathToIndex,
                        uploadSessionId != null ? uploadSessionId : "(global)");
                vectorIndexService.indexSingleFile(pathToIndex.toString(), uploadSessionId);
                logger.info("向量索引创建成功: {}", pathToIndex);
            } catch (Exception e) {
                logger.error("向量索引创建失败: {}, 错误: {}", pathToIndex, e.getMessage(), e);
                // 注意：即使索引失败，文件上传仍然成功，只是记录错误日志
                // 可以根据业务需求决定是否要删除文件或返回错误
            }

            if (uploadSessionId != null) {
                chatSessionPersistenceService.mergeLastUploadedFilename(uploadSessionId, originalFilename);
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    filePath.toString(),
                    file.getSize()
            );

            // 使用统一的API响应格式
            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(response);
            
            return ResponseEntity.ok(apiResponse);

        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * 统一 API 响应格式
     */
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    /**
     * 将已成功写入上传目录的文件再复制到 {@code knowledge.mirror-upload-to}（如 aiops-docs）。
     *
     * @return 镜像后的文件路径；未配置镜像或失败时返回 {@code null}，此时应对 {@code uploadedFile} 建索引。
     */
    /**
     * 将会话上传副本写入独立目录并向量化，路径不参与 indexed-directories 启动索引。
     */
    private Path copyToSessionRagStore(String sessionId, Path relative, Path sourceFile) throws IOException {
        String base = knowledgeProperties.getSessionRagStoreDir();
        if (base == null || base.isBlank()) {
            base = "./data/rag-session-store";
        }
        Path root = Paths.get(base.trim()).normalize();
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                    .resolve(root).normalize();
        }
        String safeSid = ChatSessionPersistenceService.safeSessionFileName(sessionId);
        Path sessionRoot = root.resolve(safeSid).normalize();
        Path targetDir = sessionRoot;
        Path parent = relative.getParent();
        if (parent != null && parent.getNameCount() > 0) {
            targetDir = sessionRoot.resolve(parent).normalize();
        }
        if (!targetDir.startsWith(sessionRoot)) {
            throw new IOException("非法会话文件目录");
        }
        Files.createDirectories(targetDir);
        Path dest = targetDir.resolve(relative.getFileName()).normalize();
        if (!dest.startsWith(sessionRoot)) {
            throw new IOException("非法会话文件路径");
        }
        Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    private Path mirrorToKnowledgeDirIfConfigured(String originalFilename, Path uploadedFile) {
        String mirror = knowledgeProperties.getMirrorUploadTo();
        if (mirror == null || mirror.isBlank()) {
            return null;
        }
        try {
            Path mirrorDir = Paths.get(mirror.trim()).normalize();
            if (!mirrorDir.isAbsolute()) {
                mirrorDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                        .resolve(mirrorDir).normalize();
            }
            Files.createDirectories(mirrorDir);
            Path relative = safeMirrorRelativePath(originalFilename);
            if (relative == null) {
                return null;
            }
            Path mirrorFile = mirrorDir.resolve(relative).normalize();
            if (!mirrorFile.startsWith(mirrorDir)) {
                logger.warn("跳过非法镜像路径（疑似穿越）: {}", mirrorFile);
                return null;
            }
            Path parent = mirrorFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(uploadedFile, mirrorFile, StandardCopyOption.REPLACE_EXISTING);
            logger.info("已按配置镜像上传文件到: {}", mirrorFile);
            return mirrorFile;
        } catch (IOException e) {
            logger.warn("镜像上传文件到知识库目录失败（不影响主上传路径）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 multipart 中的文件名规范为相对路径（支持 curl {@code -F "file=@a/b;filename=group/x.md"}），
     * 避免子目录文件被压平到镜像根目录与 aiops-docs/group 重复。
     */
    private static Path safeMirrorRelativePath(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return Paths.get("_unnamed.bin");
        }
        String n = originalFilename.replace('\\', '/').trim();
        if (n.startsWith("/")) {
            n = n.replaceFirst("^/+", "");
        }
        Path rel = Paths.get(n).normalize();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if ("..".equals(rel.getName(i).toString())) {
                return null;
            }
        }
        return rel;
    }

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        String ext = extension.toLowerCase();
        for (String token : allowedExtensions.split(",")) {
            if (ext.equals(token.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
