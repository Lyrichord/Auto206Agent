# Skill：206 服务器监控与处置（`server-monitoring`）

## 何时触发

- 「实验室/206/课题组服务器现在怎么样」
- CPU、内存、硬盘、负载、网络占用高不高
- 磁盘快满了、OOM、要不要清理

## 必选工具（按问题组合）

| 用户关注点 | 工具 | 说明 |
|------------|------|------|
| **此刻资源数值** | `getLabServerRuntimeSnapshot` | 返回 cpuPercent、memoryPercent、load1、diskRootPercent、网络速率等 |
| **处置步骤 / SOP** | `queryInternalDocs` | 命中 `disk_high_usage.md`、`memory_high_usage.md`、`cpu_high_usage.md`、`lab_server_snapshot_and_actions.md` 等 |
| **有哪些 firing 告警** | `queryPrometheusAlerts` | 告警规则列表，**不是**主机快照 |

## 作答规则

- **数值以快照为准**；**操作步骤以检索到的运维文档为准**
- 快照与告警不可混答：「内存多少」用快照，「有哪些活跃告警」用告警工具
- 快照无 GPU 利用率时，引导 `nvidia-smi` 或查阅 `gpu_high_usage.md`（见知识库 Skill）

## 前置（运维侧）

- Prometheus + node_exporter 可用；开发机常用 SSH 隧道访问 `127.0.0.1:9090`
