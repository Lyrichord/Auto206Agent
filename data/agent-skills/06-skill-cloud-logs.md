# Skill：腾讯云日志检索（`cloud-logs`）

## 何时触发

- 查某服务/主题日志、CLS、云日志、最近错误日志
- 结合告警做日志取证（可与 `server-monitoring` / AI Ops 联动）

## 必选工具（按环境）

1. **优先**：腾讯云 **CLS MCP**（`tencent-cls`）
2. **备选**：`queryLogs`（`cls.mock-enabled=true` 时为演示数据，须在答复中标注）

## 参数约定

- **region**：连字符格式，默认 **`ap-nanjing`**（合肥就近）
- **时间范围**：未说明时默认 **近一个月**

## 禁止

- 编造未查询到的日志行、RequestId、堆栈
