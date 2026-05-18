package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.config.ServerMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 从 Prometheus 拉取单台 Linux 主机（node_exporter）的 CPU/内存/负载/磁盘/网络等指标。
 */
@Service
public class ServerMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(ServerMonitorService.class);

    private static final DateTimeFormatter QUERIED_AT_DISPLAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final ServerMonitorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.timeout:10}")
    private int timeoutSeconds;

    private OkHttpClient httpClient;

    public ServerMonitorService(ServerMonitorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .readTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    public Map<String, Object> snapshot() {
        if (!properties.isEnabled()) {
            return Map.of("ok", false, "error", "server-monitor.enabled=false");
        }
        if (properties.isMockEnabled()) {
            return buildMockSnapshot();
        }
        String regex = properties.resolvedInstanceRegex();
        String inst = buildInstanceLabelSelector(regex);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("host", properties.getTargetHost());
        out.put("instanceRegex", regex);
        out.put("prometheusBaseUrl", prometheusBaseUrl);
        putQueriedAtFields(out, Instant.now());

        out.put("refreshSeconds", properties.getRefreshSeconds());
        putMetric(out, "cpuPercent", promqlCpu(inst));
        putMetric(out, "memoryPercent", promqlMemory(inst));
        putMetric(out, "load1", promqlLoad1(inst));
        putMetric(out, "diskRootPercent", promqlDiskRoot(inst));
        putMetric(out, "networkReceiveBps", promqlNetRx(inst));
        putMetric(out, "networkTransmitBps", promqlNetTx(inst));

        out.put("unitHints", Map.of(
                "cpuPercent", "% 非 idle 估算",
                "memoryPercent", "% RAM/内存已用（1 - MemAvailable/MemTotal），无 DCGM GPU 指标",
                "load1", "1 分钟平均负载",
                "diskRootPercent", "% 根分区已用",
                "networkReceiveBps", "字节/秒 入站（前端显示为 Mb/s）",
                "networkTransmitBps", "字节/秒 出站（前端显示为 Mb/s）"
        ));
        return out;
    }

    private void putMetric(Map<String, Object> out, String key, String promql) {
        try {
            OptionalDouble v = queryInstantScalar(promql);
            if (v.isPresent()) {
                double raw = v.getAsDouble();
                if ("load1".equals(key)) {
                    out.put(key, round3(raw));
                } else if (key.startsWith("network")) {
                    out.put(key, round2(raw));
                } else {
                    out.put(key, round2(raw));
                }
                out.put(key + "Error", null);
            } else {
                out.put(key, null);
                out.put(key + "Error", "无数据或查询失败（检查 instance 标签与 exporter）");
            }
        } catch (Exception e) {
            logger.warn("Prometheus 查询失败 [{}]: {}", key, e.getMessage());
            out.put(key, null);
            out.put(key + "Error", e.getMessage());
        }
    }

    private OptionalDouble queryInstantScalar(String promql) throws Exception {
        HttpUrl url = HttpUrl.parse(trimSlash(prometheusBaseUrl) + "/api/v1/query")
                .newBuilder()
                .addQueryParameter("query", promql)
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonNode root = objectMapper.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                throw new RuntimeException(root.path("error").asText("unknown"));
            }
            JsonNode results = root.path("data").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return OptionalDouble.empty();
            }
            JsonNode value = results.get(0).path("value");
            if (!value.isArray() || value.size() < 2) {
                return OptionalDouble.empty();
            }
            String s = value.get(1).asText();
            if (s == null || s.isBlank() || "NaN".equalsIgnoreCase(s)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(Double.parseDouble(s));
        }
    }

    private Map<String, Object> buildMockSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("mock", true);
        out.put("host", properties.getTargetHost());
        out.put("instanceRegex", properties.resolvedInstanceRegex());
        out.put("prometheusBaseUrl", prometheusBaseUrl);
        putQueriedAtFields(out, Instant.now());
        out.put("cpuPercent", 23.5);
        out.put("cpuPercentError", null);
        out.put("memoryPercent", 61.2);
        out.put("memoryPercentError", null);
        out.put("load1", 1.42);
        out.put("load1Error", null);
        out.put("diskRootPercent", 48.0);
        out.put("diskRootPercentError", null);
        out.put("networkReceiveBps", 125000.0);
        out.put("networkReceiveBpsError", null);
        out.put("networkTransmitBps", 88000.0);
        out.put("networkTransmitBpsError", null);
        return out;
    }

    /**
     * {@code queriedAt}：UTC ISO；{@code queriedAtDisplay}/{@code queriedAtZone}：分项；
     * {@code queriedAtText}：已拼好的墙钟+时区，供前端直接展示（避免缓存旧 JS 或 Date.parse 纳秒失败仍显示 Z）。
     */
    private void putQueriedAtFields(Map<String, Object> out, Instant when) {
        out.put("queriedAt", when.toString());
        ZoneId z = properties.resolveDisplayZoneId();
        String wall = when.atZone(z).format(QUERIED_AT_DISPLAY);
        out.put("queriedAtDisplay", wall);
        out.put("queriedAtZone", z.getId());
        out.put("queriedAtText", wall + "（" + z.getId() + "）");
    }

    private static String promqlCpu(String inst) {
        return "100 * (1 - avg(rate(node_cpu_seconds_total{" + inst + ",mode=\"idle\"}[5m])))";
    }

    private static String promqlMemory(String inst) {
        return "100 * (1 - (" +
                "node_memory_MemAvailable_bytes{" + inst + "} / node_memory_MemTotal_bytes{" + inst + "}))";
    }

    private static String promqlLoad1(String inst) {
        return "node_load1{" + inst + "}";
    }

    private static String promqlDiskRoot(String inst) {
        return "100 * max by (instance) ((" +
                "node_filesystem_size_bytes{mountpoint=\"/\",fstype!=\"rootfs\"," + inst + "} - " +
                "node_filesystem_avail_bytes{mountpoint=\"/\",fstype!=\"rootfs\"," + inst + "}) / " +
                "clamp_min(node_filesystem_size_bytes{mountpoint=\"/\",fstype!=\"rootfs\"," + inst + "}, 1))";
    }

    private static String promqlNetRx(String inst) {
        return "sum(rate(node_network_receive_bytes_total{" + inst + ",device!~\"lo|veth.*|docker.*|br-.*|virbr.*\"}[5m]))";
    }

    private static String promqlNetTx(String inst) {
        return "sum(rate(node_network_transmit_bytes_total{" + inst + ",device!~\"lo|veth.*|docker.*|br-.*|virbr.*\"}[5m]))";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String trimSlash(String u) {
        if (u == null) {
            return "";
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /**
     * Prometheus 2.50+ 对 {@code =~"..."} 内反斜杠转义更严格，{@code \.} 会报 parse error。
     * 若配置实为单台 instance（如 YAML 中的 {@code 127\\.0\\.0\\.1:9100}），改为 {@code instance="127.0.0.1:9100"}；
     * 否则将 {@code \.} 换成 RE2 的 {@code [.]}，避免非法转义。
     */
    private static String buildInstanceLabelSelector(String regex) {
        if (regex == null || regex.isBlank()) {
            return "instance=~\".+\"";
        }
        String trimmed = regex.trim();
        String literalDots = trimmed.replace("\\.", ".");
        if (isExactInstanceLiteral(literalDots)) {
            return "instance=\"" + escapePromqlDoubleQuotedString(literalDots) + "\"";
        }
        String promqlSafeRe = trimmed.replace("\\.", "[.]");
        return "instance=~\"" + escapePromqlDoubleQuotedString(promqlSafeRe) + "\"";
    }

    private static boolean isExactInstanceLiteral(String literalDots) {
        if (!literalDots.matches("[0-9A-Za-z._\\-]+:\\d+")) {
            return false;
        }
        return !literalDots.contains("*")
                && !literalDots.contains("?")
                && !literalDots.contains("|")
                && !literalDots.contains("(")
                && !literalDots.contains("[");
    }

    /** PromQL 双引号字符串内：{@code \} {@code "} 需转义 */
    private static String escapePromqlDoubleQuotedString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
