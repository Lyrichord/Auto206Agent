# Skill：告警分析与运维报告（`aiops-report`）

## 何时触发

- 用户要「告警分析报告」「AI Ops」「自动分析当前告警」「运维巡检报告」

## 推荐路径

1. **界面**：顶部 **「AI Ops」** → `POST /api/ai_ops`（Planner–Executor 多 Agent，SSE 输出《告警分析报告》）
2. **对话内手工编排**（无按钮时）按序组合工具：
   - `queryPrometheusAlerts` — 活跃告警清单
   - `getLabServerRuntimeSnapshot` — 主机资源上下文（可选）
   - `queryLogs` / 腾讯云 MCP — 日志证据
   - `queryInternalDocs` — 处置方案（`disk_high_usage` 等）
   - `getCurrentDateTime` — 报告时间戳

## 禁止

- 编造告警名称、指标数值、日志原文
- Mock 数据须在报告中标注为演示/降级来源

## 与 `server-monitoring` 的区别

- `server-monitoring`：回答用户**某一个**资源或处置问题
- `aiops-report`：输出**结构化多章节**完整报告
