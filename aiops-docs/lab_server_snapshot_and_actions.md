# 实验室服务器监控快照与处置指引（总览）

> **用途**：与 Auto206Agent 前端「206 服务器监控」及对话内 **getLabServerRuntimeSnapshot** 返回的 JSON 字段对齐；先读即时指标，再按症状打开下方专项文档执行。

## 快照字段与含义（Prometheus / node_exporter）

| JSON 字段 | 含义 | 经验阈值（课题组共用机，仅供参考） |
|-----------|------|--------------------------------------|
| `cpuPercent` | CPU 非 idle 估算占用（%） | 长期 >80% 需结合进程与训练任务排查 |
| `memoryPercent` | RAM 已用比例（1 − MemAvailable/MemTotal） | >85% 警惕 OOM；见 `memory_high_usage.md` |
| `load1` | 1 分钟平均负载 | 与 CPU 核数对比，持续高于核数说明排队严重 |
| `diskRootPercent` | 根分区 `/` 已用（%） | >80% 预警；>90% 紧急清理或扩容，见 `disk_high_usage.md` |
| `networkReceiveBps` / `networkTransmitBps` | 网卡字节/秒 | UI 常换算为 Mb/s；突发高流量结合备份/拉镜像场景判断 |
| `queriedAtText` | 查询时刻与时区 | 回答「现在」须引用该时间 |
| `*Error` 后缀字段 | 该指标拉取失败原因 | 如实告知用户，检查 `instance` 与 exporter |

若 `ok=false`，多为 `server-monitor.enabled=false` 或 Prometheus 不可达；勿编造数值。

## 与 `queryPrometheusAlerts` 的区别

- **告警列表**：`queryPrometheusAlerts` 返回的是 Prometheus **规则引擎当前 firing 的告警条目**（名称、描述等）。
- **主机资源快照**：`getLabServerRuntimeSnapshot` / 本页描述的是 **单台监控目标主机此刻** 的 CPU/内存/盘/负载/网络汇总。二者不可混用回答「内存占用多少」类问题。

## 按症状选文档

| 用户关注点 | 优先打开的运维文档 |
|------------|-------------------|
| 内存高、OOM、swap | `memory_high_usage.md` |
| 根分区或磁盘满、日志撑盘 | `disk_high_usage.md` |
| CPU 打满、负载高 | `cpu_high_usage.md` |
| GPU 显存或利用率异常 | `gpu_high_usage.md` |

## 磁盘将满时的通用动作（摘要）

1. **确认**：看 `diskRootPercent` 与 `queriedAtText`，避免用过期截图决策。  
2. **安全清理**：大日志、`/tmp`、包管理器缓存、Docker 未用镜像（需管理员评估）。  
3. **根治**：日志轮转、任务输出重定向到数据盘、扩容或迁移数据目录。  
4. **详细命令与检查清单**：以 `disk_high_usage.md` 为准。

## 内存偏高时的通用动作（摘要）

1. 用 `memoryPercent` 判断是否属实持续高占用。  
2. 登录主机后按 `memory_high_usage.md` 检查 top、容器、cgroup、大页缓存等。  
3. 训练任务：减小 batch、释放缓存、错峰跑作业；必要时联系管理员协调资源。

---

维护说明：修改 Prometheus 抓取或 `application.yml` 中 `server-monitor` 后，应同步核对本表字段含义是否仍一致。
