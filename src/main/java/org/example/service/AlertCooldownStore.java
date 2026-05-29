package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警冷却：同一 key 在冷却期内不重复推送；状态落盘，应用重启后仍有效。
 */
@Component
public class AlertCooldownStore {

    private static final Logger logger = LoggerFactory.getLogger(AlertCooldownStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Long> lastSentEpochMs = new ConcurrentHashMap<>();
    private volatile Path statePath;

    public synchronized void configureStateFile(String stateFile) {
        this.statePath = Paths.get(stateFile).toAbsolutePath().normalize();
        loadFromDisk();
    }

    /**
     * @return true 表示可以发送；false 表示仍在冷却期
     */
    public synchronized boolean tryAcquire(String alertKey, Duration cooldown) {
        Long last = lastSentEpochMs.get(alertKey);
        if (last == null) {
            return true;
        }
        long elapsed = Instant.now().toEpochMilli() - last;
        return elapsed >= cooldown.toMillis();
    }

    public synchronized void markSent(String alertKey) {
        lastSentEpochMs.put(alertKey, Instant.now().toEpochMilli());
        persist();
    }

    public synchronized Instant lastSentAt(String alertKey) {
        Long last = lastSentEpochMs.get(alertKey);
        return last == null ? null : Instant.ofEpochMilli(last);
    }

    private void loadFromDisk() {
        if (statePath == null) {
            return;
        }
        if (!Files.isRegularFile(statePath)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(statePath);
            if (bytes.length == 0) {
                return;
            }
            Map<String, Long> loaded = objectMapper.readValue(bytes, new TypeReference<>() {});
            lastSentEpochMs.clear();
            lastSentEpochMs.putAll(loaded);
            logger.info("已加载告警冷却状态 {} 条: {}", lastSentEpochMs.size(), statePath);
        } catch (Exception e) {
            logger.warn("读取告警冷却状态失败 {}: {}", statePath, e.getMessage());
        }
    }

    private void persist() {
        if (statePath == null) {
            return;
        }
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Long> snapshot = new LinkedHashMap<>(lastSentEpochMs);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), snapshot);
        } catch (IOException e) {
            logger.warn("写入告警冷却状态失败 {}: {}", statePath, e.getMessage());
        }
    }
}
