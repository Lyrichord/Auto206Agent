package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 206 组内服务器监控：通过 Prometheus 即时查询 node_exporter（及可选 DCGM）指标。
 * <p>需在 Prometheus 中已抓取该主机（常见 instance 为 {@code IP:9100}）。MobaXterm 仅 SSH 运维用，
 * 本功能不要求浏览器直连服务器 IP。</p>
 */
@Component
@ConfigurationProperties(prefix = "server-monitor")
public class ServerMonitorProperties {

    private boolean enabled = true;

    /** 无 Prometheus 或联调时返回模拟数据 */
    private boolean mockEnabled = false;

    /** 目标主机 IP，用于拼 Prometheus {@code instance=~"IP:.*"} */
    private String targetHost = "172.19.0.64";

    /**
     * 覆盖自动生成的 instance 正则；留空则使用 {@code targetHost} 转义为 {@code 172\.19\.0\.64:.*}
     */
    private String instanceRegex = "";

    /** 弹层内自动刷新间隔（秒），0 表示仅手动刷新 */
    private int refreshSeconds = 10;

    /**
     * 弹层「查询时间」展示用 IANA 时区（与 UTC 的 {@code queriedAt} 配套）。默认东八区；
     * 若 JVM 已设 {@code user.timezone} 且希望跟系统走，可改为 {@code system} 使用 {@link ZoneId#systemDefault()}。
     */
    private String displayTimezone = "Asia/Shanghai";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost != null ? targetHost.trim() : "";
    }

    public String getInstanceRegex() {
        return instanceRegex;
    }

    public void setInstanceRegex(String instanceRegex) {
        this.instanceRegex = instanceRegex != null ? instanceRegex.trim() : "";
    }

    public int getRefreshSeconds() {
        return refreshSeconds;
    }

    public void setRefreshSeconds(int refreshSeconds) {
        this.refreshSeconds = refreshSeconds;
    }

    public String getDisplayTimezone() {
        return displayTimezone;
    }

    public void setDisplayTimezone(String displayTimezone) {
        this.displayTimezone = displayTimezone;
    }

    /** 用于格式化 {@code queriedAtDisplay}；{@code system} 表示 {@link ZoneId#systemDefault()} */
    public ZoneId resolveDisplayZoneId() {
        String raw = displayTimezone == null ? "" : displayTimezone.trim();
        if (raw.isEmpty() || "system".equalsIgnoreCase(raw)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(raw);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** Prometheus label 正则，供 PromQL 使用 */
    public String resolvedInstanceRegex() {
        if (instanceRegex != null && !instanceRegex.isBlank()) {
            return instanceRegex;
        }
        String h = targetHost == null ? "" : targetHost.trim();
        if (h.isEmpty()) {
            return ".*";
        }
        return h.replace(".", "\\.") + ":.*";
    }
}
