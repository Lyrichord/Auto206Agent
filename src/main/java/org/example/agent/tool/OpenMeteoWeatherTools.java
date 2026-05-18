package org.example.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 实况与预报天气（Open-Meteo 公开接口，免 Key），与高德 MCP 路线分离。
 * 用于避免模型在未真正调用 MCP 天气工具时编造气温；回答天气时应优先依据本工具返回的 JSON。
 */
@Component
public class OpenMeteoWeatherTools {

    private static final Logger logger = LoggerFactory.getLogger(OpenMeteoWeatherTools.class);

    public static final String TOOL_GET_CITY_WEATHER_FORECAST = "getCityWeatherForecast";

    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String USER_AGENT = "Auto206Agent/1.0 (open-meteo.org)";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(Duration.ofSeconds(22))
            .build();

    @Tool(description = "Get real weather forecast for a city (Open-Meteo, no API key). "
            + "ALWAYS call this when the user asks about temperature, conditions, rain, wind, AQI-related forecast, or multi-day weather for a place. "
            + "Do not invent °C values; quote from the returned JSON (current + daily). "
            + "If the user says 'today' without a city, use cityOrPlace=合肥 or Hefei.")
    public String getCityWeatherForecast(
            @ToolParam(description = "City name in Chinese or English, e.g. 合肥, 成都, Chengdu") String cityOrPlace,
            @ToolParam(description = "Forecast days 1–7, default 3", required = false) Integer forecastDays) {
        if (cityOrPlace == null || cityOrPlace.isBlank()) {
            return errorJson("cityOrPlace is required (use 合肥 if user did not name a city)");
        }
        int days = forecastDays == null ? 3 : Math.min(7, Math.max(1, forecastDays));
        try {
            JsonObject geo = geocodeFirst(cityOrPlace.strip());
            if (geo == null) {
                return errorJson("No location found for: " + cityOrPlace);
            }
            double lat = geo.get("latitude").getAsDouble();
            double lon = geo.get("longitude").getAsDouble();
            String label = geo.has("name") ? geo.get("name").getAsString() : cityOrPlace;

            String url = FORECAST_URL + "?latitude=" + lat + "&longitude=" + lon
                    + "&timezone=auto"
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,precipitation"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max"
                    + "&forecast_days=" + days;
            String body = httpGet(url);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("source", "Open-Meteo");
            out.addProperty("query", cityOrPlace);
            out.addProperty("resolvedName", label);
            out.addProperty("latitude", lat);
            out.addProperty("longitude", lon);
            if (root.has("current")) {
                out.add("current", root.get("current"));
            }
            if (root.has("daily")) {
                out.add("daily", root.get("daily"));
            }
            if (root.has("current_units")) {
                out.add("current_units", root.get("current_units"));
            }
            if (root.has("daily_units")) {
                out.add("daily_units", root.get("daily_units"));
            }
            return out.toString();
        } catch (Exception e) {
            logger.warn("getCityWeatherForecast failed", e);
            return errorJson(e.getMessage());
        }
    }

    private JsonObject geocodeFirst(String name) throws IOException {
        String enc = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = GEOCODING_URL + "?name=" + enc + "&count=5&language=zh";
        String body = httpGet(url);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("results") || !root.get("results").isJsonArray() || root.getAsJsonArray("results").isEmpty()) {
            return null;
        }
        return root.getAsJsonArray("results").get(0).getAsJsonObject();
    }

    private String httpGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String body = rb != null ? rb.string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + body);
            }
            return body;
        }
    }

    private static String errorJson(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", message == null ? "unknown error" : message);
        return o.toString();
    }
}
