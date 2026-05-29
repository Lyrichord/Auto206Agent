package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 206 服务器监控飞书告警：定时检查 Prometheus 磁盘指标，超阈值经 Webhook 推送到飞书群。
 */
@Component
@ConfigurationProperties(prefix = "server-monitor.feishu-alert")
public class FeishuAlertProperties {

    /** 是否启用定时巡检与飞书推送 */
    private boolean enabled = false;

    /** 飞书自定义机器人 Webhook 完整 URL（含 access_token） */
    private String webhookUrl = "";

    /**
     * 机器人「签名校验」密钥；未配置则不加 sign 参数。
     */
    private String signSecret = "";

    /** 根分区磁盘使用率超过该值（%）时触发告警 */
    private double diskThresholdPercent = 90.0;

    /** 巡检间隔（秒） */
    private int checkIntervalSeconds = 300;

    /** 同一告警键发送成功后，多少小时内不再重复推送 */
    private int cooldownHours = 24;

    /** 冷却状态持久化文件（重启后仍生效） */
    private String stateFile = "./data/alert-state/feishu-disk-alert.json";

    /** mock 快照不参与告警（避免联调误报） */
    private boolean skipMockSnapshot = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : "";
    }

    public String getSignSecret() {
        return signSecret;
    }

    public void setSignSecret(String signSecret) {
        this.signSecret = signSecret != null ? signSecret.trim() : "";
    }

    public double getDiskThresholdPercent() {
        return diskThresholdPercent;
    }

    public void setDiskThresholdPercent(double diskThresholdPercent) {
        this.diskThresholdPercent = diskThresholdPercent;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(int checkIntervalSeconds) {
        this.checkIntervalSeconds = Math.max(60, checkIntervalSeconds);
    }

    public int getCooldownHours() {
        return cooldownHours;
    }

    public void setCooldownHours(int cooldownHours) {
        this.cooldownHours = Math.max(1, cooldownHours);
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile != null ? stateFile.trim() : "./data/alert-state/feishu-disk-alert.json";
    }

    public boolean isSkipMockSnapshot() {
        return skipMockSnapshot;
    }

    public void setSkipMockSnapshot(boolean skipMockSnapshot) {
        this.skipMockSnapshot = skipMockSnapshot;
    }

    public Duration cooldownDuration() {
        return Duration.ofHours(cooldownHours);
    }

    public boolean isConfigured() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }
}
