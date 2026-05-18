package org.example.controller;

import org.example.service.ResearchFeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 最近一周自动驾驶相关：arXiv 论文 + GitHub 仓库聚合。
 */
@RestController
@RequestMapping("/api/research-feed")
public class ResearchFeedController {

    private static final Logger logger = LoggerFactory.getLogger(ResearchFeedController.class);

    @Autowired
    private ResearchFeedService researchFeedService;

    @PostMapping("/start")
    public ResponseEntity<ChatController.ApiResponse<Map<String, Object>>> start() {
        try {
            Map<String, Object> data = researchFeedService.startFeed();
            if (Boolean.FALSE.equals(data.get("ok"))) {
                return ResponseEntity.ok(ChatController.ApiResponse.error(String.valueOf(data.getOrDefault("error", "unknown"))));
            }
            return ResponseEntity.ok(ChatController.ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("research-feed start 失败", e);
            return ResponseEntity.ok(ChatController.ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/more")
    public ResponseEntity<ChatController.ApiResponse<Map<String, Object>>> more(@RequestBody Map<String, String> body) {
        try {
            String sessionId = body != null ? body.get("sessionId") : null;
            Map<String, Object> data = researchFeedService.more(sessionId);
            if (Boolean.FALSE.equals(data.get("ok"))) {
                return ResponseEntity.ok(ChatController.ApiResponse.error(String.valueOf(data.getOrDefault("error", "unknown"))));
            }
            return ResponseEntity.ok(ChatController.ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("research-feed more 失败", e);
            return ResponseEntity.ok(ChatController.ApiResponse.error(e.getMessage()));
        }
    }
}
