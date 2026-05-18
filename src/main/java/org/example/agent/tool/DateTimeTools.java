package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class DateTimeTools {
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";

    private static final DateTimeFormatter ZH_FMT =
            DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss z", Locale.CHINA);
    
    @Tool(description = "Returns the server's current date and time (authoritative). "
            + "You MUST call this when the user asks what day/today is, weekday, calendar date, or 'now' in a time sense; "
            + "never guess dates from memory or from retrieved documents.")
    public String getCurrentDateTime() {
        // 与 ChatService 系统提示【系统时钟】一致，避免与 JVM 默认时区不一致
        ZonedDateTime zdt = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        String iso = zdt.toString();
        String zh = zdt.format(ZH_FMT);
        return "iso8601=" + iso + "\nlocalized=" + zh + "\n(Use localized line for user-facing Chinese answers; iso for machine parsing.)";
    }
}
