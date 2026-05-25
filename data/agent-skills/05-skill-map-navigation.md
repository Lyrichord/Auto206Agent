# Skill：地图与路线规划（`map-navigation`）

## 何时触发

- 驾车/步行/骑行/公交/地铁路线
- 地点搜索、周边 POI、测距、导航
- 「从 A 到 B 怎么走」

## 必选工具

- **魔搭 Hosted 高德 MCP**（连接名 **`amap-maps`**）所注册的工具

## 默认地理语境

- 用户未说明城市时，与地理位置相关的查询默认 **安徽省合肥市**（含磬苑校区、之心城等校内常用地）

## 禁止

- 未调用 MCP 即编造路程时间、站点、坐标
- 将路线问题交给 `queryInternalDocs`（除非用户明确问组内文档里的地址说明）

## 前置

- `application-local.yml` 中配置 `spring.ai.mcp.client.sse.connections.amap-maps.sse-endpoint`
