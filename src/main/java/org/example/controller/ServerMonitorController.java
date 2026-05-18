package org.example.controller;

import org.example.service.ServerMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 206 服务器运行监控：前端按钮拉取 Prometheus 即时指标。
 */
@RestController
@RequestMapping("/api/server-monitor")
public class ServerMonitorController {

    private static final Logger logger = LoggerFactory.getLogger(ServerMonitorController.class);

    @Autowired
    private ServerMonitorService serverMonitorService;

    @GetMapping("/snapshot")
    public ResponseEntity<ChatController.ApiResponse<Map<String, Object>>> snapshot() {
        try {
            Map<String, Object> data = serverMonitorService.snapshot();
            Boolean ok = (Boolean) data.get("ok");
            if (ok != null && !ok) {
                return ResponseEntity.ok(ChatController.ApiResponse.error(String.valueOf(data.getOrDefault("error", "unknown"))));
            }
            return ResponseEntity.ok(ChatController.ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("server-monitor snapshot 失败", e);
            return ResponseEntity.ok(ChatController.ApiResponse.error(e.getMessage()));
        }
    }
}
