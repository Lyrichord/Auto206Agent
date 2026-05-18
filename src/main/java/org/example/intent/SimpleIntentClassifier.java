package org.example.intent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 基于关键词与浅层规则的意图分类，无外部模型调用。
 * <p>
 * 多类同时强命中时退化为 {@link ChatIntent#GENERAL}，避免错误收窄工具链。
 */
@Component
public class SimpleIntentClassifier {

    private static final Pattern TIME_LIKE = Pattern.compile(
            "(几号|星期|礼拜|周几|哪天|何时|几点|时刻|日期|北京时间|公历|农历)"
                    + "|(今天|今日|现在)\\s*(是)?\\s*[\\d一二三四五六七八九十]{1,4}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WEATHER_STRONG = Pattern.compile(
            "(天气|气温|温度|摄氏度|℃|°c|降雨|下雨|下雪|冰雹|雾霾|风力|风向|风速|空气质量|aqi|预报|冷暖)");

    private static final Pattern MAP_STRONG = Pattern.compile(
            "(导航|路线|怎么走|怎么去|驾车|开车|步行|骑行|公交|地铁|周边|附近有什么|poi|地图测距|两地距离)");

    private static final Pattern LOG_STRONG = Pattern.compile("(云日志|检索日志|查日志|cls\\b|日志主题|log\\s*topic)");

    private static final Pattern MONITOR_STRONG = Pattern.compile(
            "(prometheus|告警|firing|宕机|磁盘满|磁盘.*占用|内存.*占用|cpu.*占用|负载高|网卡.*流量"
                    + "|服务器.*监控|206.*服务器|实验室.*服务器|gpu|显存|nvidia-smi|node_exporter)");

    private static final Pattern KNOWLEDGE_STRONG = Pattern.compile(
            "(课题组成员|组成员|导师|硕士生|博士生|邮箱|研究方向|论文|在研项目|江淮汽车|自然科学基金"
                    + "|运维文档|处置流程|sop|disk_high|memory_high|cpu_high|gpu_high|服务不可用|响应时间)");

    /**
     * @param userMessage 当前轮用户原文（未带预检索前缀）
     */
    public ChatIntent classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return ChatIntent.GENERAL;
        }
        String s = userMessage.strip();
        String low = s.toLowerCase(Locale.ROOT);

        boolean weather = WEATHER_STRONG.matcher(s).find() || low.matches(".*\\b(weather|forecast|temperature|rain|humidity)\\b.*");
        boolean map = MAP_STRONG.matcher(s).find() || low.matches(".*\\b(navigation|directions|route\\b|nearby\\s+poi)\\b.*");
        boolean time = TIME_LIKE.matcher(s).find() || low.matches(".*\\b(what\\s+time|what\\s+date|what\\s+day)\\b.*");
        boolean logs = LOG_STRONG.matcher(s).find();
        boolean monitor = MONITOR_STRONG.matcher(s).find();
        boolean knowledge = KNOWLEDGE_STRONG.matcher(s).find();

        int hits = 0;
        if (weather) {
            hits++;
        }
        if (map) {
            hits++;
        }
        if (time) {
            hits++;
        }
        if (logs) {
            hits++;
        }
        if (monitor) {
            hits++;
        }
        if (knowledge) {
            hits++;
        }
        if (hits > 1) {
            return ChatIntent.GENERAL;
        }

        if (weather) {
            return ChatIntent.WEATHER;
        }
        if (map) {
            return ChatIntent.MAP_NAVIGATION;
        }
        if (time) {
            return ChatIntent.TIME_DATE;
        }
        if (logs) {
            return ChatIntent.CLOUD_LOGS;
        }
        if (monitor) {
            return ChatIntent.MONITORING;
        }
        if (knowledge) {
            return ChatIntent.INTERNAL_KNOWLEDGE;
        }
        return ChatIntent.GENERAL;
    }
}
