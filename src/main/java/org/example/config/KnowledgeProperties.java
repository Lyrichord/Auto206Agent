package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库目录与启动时向量化：仓库内 {@code aiops-docs} 与上传目录需写入 Milvus 后，
 * {@code queryInternalDocs} 才能检索到内容。
 */
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {

    /**
     * 应用启动后是否对 {@link #indexedDirectories} 执行全量索引（与单文件上传索引逻辑一致）。
     */
    private boolean bootstrapIndexOnStartup = true;

    /**
     * 启动时扫描并索引的目录（相对路径相对进程工作目录），默认包含运维文档与上传目录。
     */
    private List<String> indexedDirectories = new ArrayList<>(List.of("./aiops-docs"));

    /**
     * 上传成功后是否将文件再复制一份到此目录（便于与仓库内运维文档同级管理）。留空则不复制。
     * 注意：复制到已纳入 Git 的目录可能产生未跟踪变更，可按需关闭或配合 .gitignore。
     */
    private String mirrorUploadTo = "";

    /**
     * 带 sessionId 的会话上传：向量化用的副本目录（勿列入 indexed-directories）。
     * 若镜像到 aiops-docs 且启动全量索引，会把同文件打成 global，导致其他会话也能检索——故会话上传只索引本目录下副本。
     */
    private String sessionRagStoreDir = "./data/rag-session-store";

    /**
     * 为 true 时：上传接口携带的 sessionId 会写入 Milvus 元数据，仅该会话对话可检索到该文件（与全局运维文档并存）。
     */
    private boolean sessionScopedUploads = true;

    /**
     * 为 true 时：对话线程若设置了会话 ID，则检索仅返回「全局文档」+「本会话上传」；关闭则恢复全库检索。
     */
    private boolean sessionScopedSearch = true;

    public boolean isBootstrapIndexOnStartup() {
        return bootstrapIndexOnStartup;
    }

    public void setBootstrapIndexOnStartup(boolean bootstrapIndexOnStartup) {
        this.bootstrapIndexOnStartup = bootstrapIndexOnStartup;
    }

    public List<String> getIndexedDirectories() {
        return indexedDirectories;
    }

    public void setIndexedDirectories(List<String> indexedDirectories) {
        this.indexedDirectories = indexedDirectories != null ? indexedDirectories : new ArrayList<>();
    }

    public String getMirrorUploadTo() {
        return mirrorUploadTo;
    }

    public void setMirrorUploadTo(String mirrorUploadTo) {
        this.mirrorUploadTo = mirrorUploadTo != null ? mirrorUploadTo : "";
    }

    public String getSessionRagStoreDir() {
        return sessionRagStoreDir;
    }

    public void setSessionRagStoreDir(String sessionRagStoreDir) {
        this.sessionRagStoreDir = sessionRagStoreDir != null ? sessionRagStoreDir : "./data/rag-session-store";
    }

    public boolean isSessionScopedUploads() {
        return sessionScopedUploads;
    }

    public void setSessionScopedUploads(boolean sessionScopedUploads) {
        this.sessionScopedUploads = sessionScopedUploads;
    }

    public boolean isSessionScopedSearch() {
        return sessionScopedSearch;
    }

    public void setSessionScopedSearch(boolean sessionScopedSearch) {
        this.sessionScopedSearch = sessionScopedSearch;
    }
}
