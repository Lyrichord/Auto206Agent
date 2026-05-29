package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.example.config.FeishuAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书自定义机器人 Webhook 推送（文本消息）。
 */
@Service
public class FeishuNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(FeishuNotificationService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final FeishuAlertProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OkHttpClient httpClient;

    public FeishuNotificationService(FeishuAlertProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * @return true 表示飞书返回成功
     */
    public boolean sendText(String text) {
        if (!properties.isConfigured()) {
            logger.debug("飞书告警未启用或未配置 webhook，跳过发送");
            return false;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("msg_type", "text");
            ObjectNode content = root.putObject("content");
            content.put("text", text);
            String body = objectMapper.writeValueAsString(root);

            String requestUrl = buildSignedUrlIfNeeded(properties.getWebhookUrl(), properties.getSignSecret());
            Request request = new Request.Builder()
                    .url(requestUrl)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    logger.warn("飞书 Webhook HTTP {}: {}", response.code(), respBody);
                    return false;
                }
                if (respBody.contains("\"StatusCode\":0")
                        || respBody.contains("\"code\":0")
                        || respBody.contains("\"ok\":true")) {
                    logger.info("飞书告警发送成功");
                    return true;
                }
                logger.warn("飞书 Webhook 响应异常: {}", respBody);
                return false;
            }
        } catch (Exception e) {
            logger.error("飞书告警发送失败", e);
            return false;
        }
    }

    static String buildSignedUrlIfNeeded(String webhookUrl, String signSecret) throws Exception {
        if (signSecret == null || signSecret.isBlank()) {
            return webhookUrl;
        }
        long timestamp = System.currentTimeMillis() / 1000;
        String stringToSign = timestamp + "\n" + signSecret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);

        HttpUrl base = HttpUrl.parse(webhookUrl);
        if (base == null) {
            throw new IllegalArgumentException("invalid feishu webhook url");
        }
        return base.newBuilder()
                .addQueryParameter("timestamp", String.valueOf(timestamp))
                .addQueryParameter("sign", sign)
                .build()
                .toString();
    }
}
