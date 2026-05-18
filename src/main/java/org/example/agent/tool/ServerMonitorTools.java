package org.example.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ServerMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 将「206 服务器监控」同源 Prometheus 快照暴露为 Agent 工具，供对话中询问实验室主机运行状态时调用。
 */
@Component
public class ServerMonitorTools {

    private static final Logger logger = LoggerFactory.getLogger(ServerMonitorTools.class);

    public static final String TOOL_GET_LAB_SERVER_RUNTIME_SNAPSHOT = "getLabServerRuntimeSnapshot";

    private final ServerMonitorService serverMonitorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ServerMonitorTools(ServerMonitorService serverMonitorService) {
        this.serverMonitorService = serverMonitorService;
    }

    /**
     * 拉取课题组监控目标主机（application.yml 中 server-monitor）的即时指标：
     * CPU 非 idle%、内存已用%、1 分钟负载、根分区已用%、网络收发字节/秒等；与前端 GET /api/server-monitor/snapshot 同源。
     */
    @Tool(description = "Fetch **current** lab/shared Linux host metrics from Prometheus (node_exporter): cpuPercent, memoryPercent, "
            + "load1, diskRootPercent, networkReceiveBps, networkTransmitBps, queriedAtText, per-field *Error if missing. "
            + "Use when the user asks how the **lab server / 206 server / 课题组服务器** is doing right now (CPU, RAM, disk, load, network), "
            + "or whether memory/disk is high—not the same as queryPrometheusAlerts (that lists **firing alert rules**). "
            + "If ok=false, explain the error honestly. Combine with queryInternalDocs for playbooks (disk_high_usage, memory_high_usage, cpu_high_usage).")
    public String getLabServerRuntimeSnapshot() {
        Map<String, Object> snap = serverMonitorService.snapshot();
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snap);
            logger.info("getLabServerRuntimeSnapshot ok={}", snap.get("ok"));
            return json;
        } catch (JsonProcessingException e) {
            logger.error("序列化监控快照失败", e);
            return "{\"ok\":false,\"error\":\"serialize_failed\",\"detail\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
