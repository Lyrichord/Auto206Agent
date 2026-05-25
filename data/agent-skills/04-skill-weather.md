# Skill：天气查询（`weather`）

## 何时触发

- 气温、阴晴、雨雪、风力、空气质量、多日预报
- 「今天/明天天气怎么样」（未指定城市时默认 **合肥 / Hefei**）

## 必选工具

- **`getCityWeatherForecast`**（Open-Meteo，免 Key）

## 禁止

- 未调用工具即写出具体 ℃、风力、AQI 等数字
- 用 `queryInternalDocs` 猜「今天几号」对应的天气（日期类走 `time-date` Skill）

## 可选

- 若已配置高德 MCP 且其天气类工具调用成功，可与其结果对照；失败时仍以 Open-Meteo 为准
