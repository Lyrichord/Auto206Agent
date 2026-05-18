package org.example.intent;

/**
 * 主对话轻量级意图标签（规则命中），用于系统提示中的路线指引。
 */
public enum ChatIntent {

    /** 未归入下列类别或规则冲突时 */
    GENERAL(""),

    /** 当前公历日期、时刻、星期等 */
    TIME_DATE("【意图】时间/日期：必须先调用 getCurrentDateTime，不要用 queryInternalDocs 猜日期。\n"),

    /** 实况与预报天气 */
    WEATHER("【意图】天气：必须先调用 getCityWeatherForecast，禁止编造气温与风力数值。\n"),

    /** 高德 MCP：路线、POI、出行 */
    MAP_NAVIGATION("【意图】地图/路线/POI/导航：使用魔搭 Hosted 高德 MCP（连接名 amap-maps）的工具。\n"),

    /** 腾讯云日志 / queryLogs */
    CLOUD_LOGS("【意图】日志检索：使用腾讯云 MCP 或 queryLogs；地域与时间范围遵循系统说明。\n"),

    /** Prometheus 告警、主机快照、资源类排查 */
    MONITORING("【意图】监控/告警/主机资源：queryPrometheusAlerts、getLabServerRuntimeSnapshot 与 aiops-docs 处置文档配合。\n"),

    /** 组成员、论文、项目、运维文档等知识检索 */
    INTERNAL_KNOWLEDGE("【意图】组内知识与运维文档：必须调用 queryInternalDocs（用户消息已含「【背景摘录】」时可直接依据摘录）。\n");

    private final String systemPromptHint;

    ChatIntent(String systemPromptHint) {
        this.systemPromptHint = systemPromptHint;
    }

    /**
     * 写入系统提示的单句指引；{@link #GENERAL} 为空串。
     */
    public String getSystemPromptHint() {
        return systemPromptHint;
    }
}
