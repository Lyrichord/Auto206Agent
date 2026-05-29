package org.example.service;

import org.example.config.FeishuAlertProperties;
import org.example.config.ServerMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 定时检查实验室服务器根分区磁盘使用率，超阈值经飞书 Webhook 告警；同一主机 24h 内不重复推送。
 */
@Component
public class ServerMonitorFeishuAlertScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ServerMonitorFeishuAlertScheduler.class);
    private static final String ALERT_KEY_DISK_ROOT = "disk-root-critical";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final FeishuAlertProperties alertProperties;
    private final ServerMonitorProperties monitorProperties;
    private final ServerMonitorService serverMonitorService;
    private final FeishuNotificationService feishuNotificationService;
    private final AlertCooldownStore cooldownStore;

    public ServerMonitorFeishuAlertScheduler(
            FeishuAlertProperties alertProperties,
            ServerMonitorProperties monitorProperties,
            ServerMonitorService serverMonitorService,
            FeishuNotificationService feishuNotificationService,
            AlertCooldownStore cooldownStore) {
        this.alertProperties = alertProperties;
        this.monitorProperties = monitorProperties;
        this.serverMonitorService = serverMonitorService;
        this.feishuNotificationService = feishuNotificationService;
        this.cooldownStore = cooldownStore;
    }

    @PostConstruct
    public void init() {
        cooldownStore.configureStateFile(alertProperties.getStateFile());
        if (alertProperties.isConfigured()) {
            logger.info("飞书磁盘告警已启用：阈值 {}%，巡检 {}s，冷却 {}h",
                    alertProperties.getDiskThresholdPercent(),
                    alertProperties.getCheckIntervalSeconds(),
                    alertProperties.getCooldownHours());
        }
    }

    /** 应用就绪后立即巡检一次（不等到 5 分钟间隔）；仍需 Prometheus 可达且磁盘超阈值。 */
    @EventListener(ApplicationReadyEvent.class)
    public void checkDiskOnStartup() {
        if (!alertProperties.isConfigured()) {
            return;
        }
        logger.info("飞书磁盘告警：应用已就绪，执行启动后首次巡检");
        checkDiskAndAlert();
    }

    @Scheduled(fixedDelayString = "${server-monitor.feishu-alert.check-interval-seconds:300}000",
            initialDelayString = "${server-monitor.feishu-alert.check-interval-seconds:300}000")
    public void checkDiskAndAlert() {
        if (!alertProperties.isConfigured()) {
            return;
        }
        if (!monitorProperties.isEnabled()) {
            return;
        }

        Map<String, Object> snap;
        try {
            snap = serverMonitorService.snapshot();
        } catch (Exception e) {
            logger.warn("飞书告警巡检：拉取监控快照失败: {}", e.getMessage());
            return;
        }

        if (!Boolean.TRUE.equals(snap.get("ok"))) {
            logger.warn("飞书告警巡检：监控快照 ok=false，未推送（{}）", snap.get("error"));
            return;
        }
        if (alertProperties.isSkipMockSnapshot() && Boolean.TRUE.equals(snap.get("mock"))) {
            logger.info("飞书告警巡检：当前为 mock 快照，跳过推送");
            return;
        }

        Double diskPercent = asDouble(snap.get("diskRootPercent"));
        if (diskPercent == null) {
            Object err = snap.get("diskRootPercentError");
            logger.warn("飞书告警巡检：未获取到 diskRootPercent，未推送。原因: {}。请确认 Prometheus 隧道/服务可用（{}）",
                    err, snap.get("prometheusBaseUrl"));
            return;
        }

        double threshold = alertProperties.getDiskThresholdPercent();
        if (diskPercent < threshold) {
            logger.info("飞书告警巡检：磁盘 {}% 未达阈值 ≥{}%，不推送", diskPercent, threshold);
            return;
        }

        String alertKey = ALERT_KEY_DISK_ROOT + ":" + monitorProperties.getTargetHost();
        if (!cooldownStore.tryAcquire(alertKey, alertProperties.cooldownDuration())) {
            Instant last = cooldownStore.lastSentAt(alertKey);
            logger.info("飞书磁盘告警仍在冷却期（上次 {}），24h 内不重复推送", last);
            return;
        }

        String host = String.valueOf(snap.getOrDefault("host", monitorProperties.getTargetHost()));
        String queriedAt = String.valueOf(snap.getOrDefault("queriedAtText", TIME_FMT.format(Instant.now())));
        String message = buildAlertMessage(host, diskPercent, queriedAt,
                alertProperties.getDiskThresholdPercent(), alertProperties.getCooldownHours());

        if (feishuNotificationService.sendText(message)) {
            cooldownStore.markSent(alertKey);
            logger.info("飞书磁盘告警已推送：host={} disk={}%", host, diskPercent);
        } else {
            logger.warn("飞书磁盘告警发送失败（未进入冷却期，下次巡检将重试）");
        }
    }

    private static String buildAlertMessage(String host, double diskPercent, String queriedAt,
                                            double threshold, int cooldownHours) {
        return "【206服务器告警】根分区磁盘使用率过高\n"
                + "主机: " + host + "\n"
                + "磁盘已用: " + String.format("%.1f", diskPercent) + "%（阈值 ≥" + threshold + "%）\n"
                + "查询时间: " + queriedAt + "\n"
                + "请尽快清理日志/缓存或扩容，可参考知识库 disk_high_usage.md。\n"
                + "（本条告警后 " + cooldownHours + " 小时内不再重复推送）";
    }

    private static Double asDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
