package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.config.ChatMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 将会话消息持久化到本地 JSON 文件，进程重启后可恢复 Agent 上下文。
 */
@Service
public class ChatSessionPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionPersistenceService.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public ChatSessionPersistenceService(ChatMemoryProperties properties) {
        this.baseDir = Paths.get(properties.getDirectory()).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建对话记忆目录: " + this.baseDir, e);
        }
    }

    /**
     * 仅允许安全文件名，防止路径穿越。
     */
    public static String safeSessionFileName(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "_empty";
        }
        String cleaned = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            return "_empty";
        }
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "_invalid";
        }
        return cleaned;
    }

    public Optional<PersistedSession> load(String sessionId) {
        Path file = sessionFile(sessionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            PersistedSession session = objectMapper.readValue(file.toFile(), PersistedSession.class);
            if (session.getMessages() == null) {
                session.setMessages(new ArrayList<>());
            }
            return Optional.of(session);
        } catch (IOException e) {
            logger.warn("读取会话文件失败: {}", file, e);
            return Optional.empty();
        }
    }

    public void save(String sessionId, long createTime, long updatedAt, List<Map<String, String>> messages,
                     String lastUploadedFilename, String rollingSummary) {
        Path file = sessionFile(sessionId);
        PersistedSession snapshot = new PersistedSession();
        snapshot.setSessionId(sessionId);
        snapshot.setCreateTime(createTime);
        snapshot.setUpdatedAt(updatedAt);
        snapshot.setMessages(copyMessages(messages));
        snapshot.setLastUploadedFilename(lastUploadedFilename);
        snapshot.setRollingSummary(rollingSummary);
        try {
            objectMapper.writeValue(file.toFile(), snapshot);
        } catch (IOException e) {
            logger.error("写入会话文件失败: {}", file, e);
        }
    }

    /**
     * 记录本会话最近一次上传的原始文件名（与会话绑定，用于 RAG 指代「这篇月报」等）。
     * 若尚无会话文件则创建仅含该字段与空消息列表的占位，后续对话保存会覆盖合并。
     */
    public void mergeLastUploadedFilename(String sessionId, String originalFilename) {
        if (sessionId == null || sessionId.isBlank() || originalFilename == null || originalFilename.isBlank()) {
            return;
        }
        PersistedSession p = load(sessionId).orElseGet(() -> {
            PersistedSession x = new PersistedSession();
            x.setSessionId(sessionId.trim());
            x.setCreateTime(System.currentTimeMillis());
            x.setMessages(new ArrayList<>());
            return x;
        });
        p.setLastUploadedFilename(originalFilename.trim());
        p.setUpdatedAt(System.currentTimeMillis());
        try {
            objectMapper.writeValue(sessionFile(sessionId).toFile(), p);
        } catch (IOException e) {
            logger.error("合并最近上传文件名到会话文件失败: {}", sessionId, e);
        }
    }

    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(sessionFile(sessionId));
        } catch (IOException e) {
            logger.warn("删除会话文件失败: {}", sessionId, e);
        }
    }

    public List<SessionListItem> listSessions() {
        List<SessionListItem> items = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) {
            return items;
        }
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            PersistedSession s = objectMapper.readValue(path.toFile(), PersistedSession.class);
                            if (s.getSessionId() == null || s.getMessages() == null) {
                                return;
                            }
                            SessionListItem item = new SessionListItem();
                            item.setId(s.getSessionId());
                            item.setTitle(inferTitle(s.getMessages()));
                            item.setUpdatedAt(s.getUpdatedAt() > 0 ? s.getUpdatedAt() : s.getCreateTime());
                            item.setMessagePairCount(s.getMessages().size() / 2);
                            items.add(item);
                        } catch (IOException e) {
                            logger.debug("跳过无法解析的会话文件: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            logger.warn("列出会话目录失败: {}", baseDir, e);
        }
        items.sort(Comparator.comparingLong(SessionListItem::getUpdatedAt).reversed());
        return items;
    }

    private static String inferTitle(List<Map<String, String>> messages) {
        for (Map<String, String> m : messages) {
            if ("user".equalsIgnoreCase(m.get("role"))) {
                String c = m.get("content");
                if (c != null && !c.isEmpty()) {
                    return c.length() > 30 ? c.substring(0, 30) + "..." : c;
                }
            }
        }
        return "新对话";
    }

    private static List<Map<String, String>> copyMessages(List<Map<String, String>> messages) {
        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> m : messages) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("role", m.get("role"));
            one.put("content", m.get("content"));
            copy.add(one);
        }
        return copy;
    }

    private Path sessionFile(String sessionId) {
        return baseDir.resolve(safeSessionFileName(sessionId) + ".json");
    }

    public static class PersistedSession {
        private String sessionId;
        private long createTime;
        private long updatedAt;
        private List<Map<String, String>> messages;
        /** 本会话最近一次带 sessionId 上传的原始文件名，用于消解「这篇月报」等指代 */
        private String lastUploadedFilename;

        /**
         * 早期多轮对话经模型压缩后的会话摘要；与 {@link #messages} 中保留的最近原文轮次一并恢复。
         */
        private String rollingSummary;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }

        public List<Map<String, String>> getMessages() {
            return messages;
        }

        public void setMessages(List<Map<String, String>> messages) {
            this.messages = messages;
        }

        public String getLastUploadedFilename() {
            return lastUploadedFilename;
        }

        public void setLastUploadedFilename(String lastUploadedFilename) {
            this.lastUploadedFilename = lastUploadedFilename;
        }

        public String getRollingSummary() {
            return rollingSummary;
        }

        public void setRollingSummary(String rollingSummary) {
            this.rollingSummary = rollingSummary;
        }
    }

    public static class SessionListItem {
        private String id;
        private String title;
        private long updatedAt;
        private int messagePairCount;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }

        public int getMessagePairCount() {
            return messagePairCount;
        }

        public void setMessagePairCount(int messagePairCount) {
            this.messagePairCount = messagePairCount;
        }
    }
}
