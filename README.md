# Auto206Agent

> 206 实验室课题组智能助手：基于 Spring Boot + AI Agent 的 RAG 问答、组内知识库与服务器运维辅助。

## 📖 项目简介

Auto206Agent 面向课题组场景，在 RAG 与工具调用基础上支持组内文档与运维知识，主要能力包括：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 **Planner → Executor → Replanner** 多 Agent 协作，实现告警分析、日志查询、智能诊断和《告警分析报告》生成。

### 3. 扩展能力（对话内自然语言触发）
除知识库问答外，主对话 Agent 还可按需调用：**组内服务器实时指标**、**Prometheus 告警**、**天气（Open-Meteo）**、**路线/POI（高德 MCP）**、**腾讯云日志（CLS MCP）** 等工具；Web 侧栏另提供 **206 监控面板** 与 **自动驾驶文献聚合**。

## 🚀 核心特性

- ✅ **RAG 问答**: 混合检索 + Rerank + 多轮对话 + SSE 流式输出
- ✅ **组内知识库**: `aiops-docs`（成员、论文、项目、运维处置）+ 会话内上传隔离
- ✅ **206 服务器监控**: Prometheus 即时快照（CPU/内存/负载/磁盘/网络）
- ✅ **AIOps 运维**: 多 Agent 协作 + 告警/日志/文档联动 + 自动报告
- ✅ **天气查询**: Open-Meteo（免 Key，工具 `getCityWeatherForecast`）
- ✅ **地图与路线**: 魔搭 Hosted 高德 MCP（驾车/步行/POI/周边等）
- ✅ **文献聚合**: 最近一周 arXiv + GitHub「自动驾驶」相关条目
- ✅ **会话管理**: JSON 持久化、历史列表、可选滚动摘要
- ✅ **Web 界面**: Vue 风格静态页 + RESTful API

## 📚 功能使用指南

以下功能均可在 **Web 聊天框**（`http://localhost:9900`）用自然语言提问；Agent 会自动选择工具。请求体中的会话字段为 **`Id`**（与 `sessionId` / `id` 兼容），上传文件时建议传相同 `sessionId` 以保持「本会话私有文档」隔离。

### 组内知识库（成员 / 论文 / 项目 / 运维文档）

**知识来源**

| 来源 | 说明 |
|------|------|
| `aiops-docs/` | 启动时自动索引（`knowledge.indexed-directories`），全会话可见 |
| `aiops-docs/group/` | 成员表 `members.md`、论文 `publications.md`、项目 `projects.md` 等 |
| 会话上传 | `POST /api/upload` 且带 `sessionId` 时，仅当前会话可检索（`knowledge.session-scoped-uploads`） |

**示例提问**

- 「刘涛是几年级硕士？导师是谁？」
- 「课题组最近有哪些论文？」
- 「江淮汽车项目对接同学是谁？」
- 「磁盘快满了怎么办？」（会检索 `disk_high_usage.md` 等处置文档）

**上传本会话材料**

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@你的笔记.md" \
  -F "sessionId=session-123"
```

支持格式：`txt, md, markdown, doc, docx, pdf`（见 `file.upload.allowed-extensions`）。

---

### 查询组内服务器状况

有两种互补方式：**可视化监控面板** 与 **对话 + Agent 工具**。

#### 方式一：Web「206 监控」面板（推荐快速查看）

1. 配置 Prometheus 与 `node_exporter`，并在 `application.yml` 中设置 `server-monitor`（见下方 [服务器监控配置](#服务器监控配置)）。
2. 打开 `http://localhost:9900`，点击侧栏或顶栏 **「206 监控」**。
3. 面板调用 `GET /api/server-monitor/snapshot`，展示 CPU、内存、1 分钟负载、根分区磁盘、网络收发等，并按 `refresh-seconds` 自动刷新。

```bash
curl http://localhost:9900/api/server-monitor/snapshot
```

#### 方式二：在对话中询问「现在服务器怎么样」

Agent 会调用 **`getLabServerRuntimeSnapshot`**（与上述接口同源），并结合 `aiops-docs` 中的处置文档回答，例如：

- 「206 服务器现在内存占用多少？」
- 「实验室共用机 CPU 高不高？」
- 「根分区快满了怎么处理？」

**与告警列表的区别**

| 工具 / 接口 | 含义 |
|-------------|------|
| `getLabServerRuntimeSnapshot` / `/api/server-monitor/snapshot` | **当前** 主机资源快照（CPU%、内存%、负载、磁盘% 等） |
| `queryPrometheusAlerts` | Prometheus **正在 firing 的告警规则** 列表 |

二者不可混用：问「内存占用多少」应看快照；问「有哪些活跃告警」应查告警工具。

相关说明文档：`aiops-docs/lab_server_snapshot_and_actions.md`。

---

### AIOps 智能运维

#### 做什么

一键触发 **规划 → 执行 → 再规划** 闭环：拉取 Prometheus 告警、（可选）查询 CLS 日志、检索 `aiops-docs` 处置方案、汇总证据后输出结构化 **《告警分析报告》**（Markdown）。

#### 怎么用

**Web（推荐）**

1. 启动服务并确保 `DASHSCOPE_API_KEY` 已配置。
2. 打开 `http://localhost:9900`，点击顶部 **「AI Ops」**。
3. 等待 SSE 流式输出完整报告（无需额外请求体）。

**API**

```bash
curl -N -X POST http://localhost:9900/api/ai_ops \
  -H "Accept: text/event-stream"
```

响应为 **SSE**（`text/event-stream`），与 `/api/chat_stream` 类似。

#### 参与的工具（Planner / Executor 共用）

| 工具 | 作用 |
|------|------|
| `queryPrometheusAlerts` | 查询活跃告警 |
| `getLabServerRuntimeSnapshot` | 主机资源快照 |
| `queryInternalDocs` | 检索运维/处置文档 |
| `queryLogs` | 日志（`cls.mock-enabled=true` 时为演示数据） |
| `getCityWeatherForecast` | 天气（AIOps 流程中一般不用，但已注册） |
| `DateTimeTools` | 当前时间等 |
| 腾讯云 MCP | 真实 CLS 查询（需配置 SSE 端点） |

#### 前置条件与说明

1. **Prometheus**：`prometheus.base-url` 指向可访问的 Prometheus；无真实环境时可设 `prometheus.mock-enabled: true` 或依赖 `fallback-mock-on-error` 使用演示告警。
2. **向量库**：`aiops-docs` 已索引（`make init` 或启动时 `knowledge.bootstrap-index-on-startup: true`）。
3. **日志**：当前 `QueryLogsTools` 在 `cls.mock-enabled: true` 时返回模拟日志；对接真实 CLS 后改为 `false` 并配置腾讯云 MCP。
4. **诚实性**：连续多次工具失败时，报告会在结论中说明原因，不会编造指标。

架构入口：`AiOpsService` → `SupervisorAgent` 调度 `planner_agent` 与 `executor_agent`。

---

### 天气查询

使用 **Open-Meteo** 公开 API（**无需 API Key**），工具名：`getCityWeatherForecast`。

**示例提问**

- 「合肥今天天气怎么样？」
- 「成都未来三天会下雨吗？」
- 「北京气温和风速？」（未指定城市时，系统提示默认倾向 **合肥**）

Agent **必须先调用工具** 再回答，禁止编造具体℃、风力等数值。

---

### 旅游路线 / 地图 / POI（高德 MCP）

驾车、步行、骑行、公交路线，地点搜索、周边 POI、测距、导航等，通过 **魔搭 MCP 广场 Hosted SSE** 接入的高德地图服务（连接名 `amap-maps`）。

**示例提问**

- 「从安徽大学磬苑校区到合肥南站开车多久？」
- 「合肥附近有什么适合团建的餐厅？」
- 「步行从 A 到 B 怎么走？」

**配置（必做其一）**

1. 复制 `application-local.yml.example` → `application-local.yml`（已 gitignore）。
2. 填写 `spring.ai.mcp.client.sse.connections.amap-maps.sse-endpoint`（魔搭 Hosted 分配的路径）。
3. 或通过环境变量：`MCP_AMAP_SSE_ENDPOINT`。

未配置 MCP 时，地图类问题可能无法调用工具；天气仍可用 Open-Meteo。

地理位置未说明时，Agent 默认按 **安徽省合肥市** 理解。

---

### 自动驾驶文献（arXiv + GitHub）

侧栏 **「自动驾驶文献」** 聚合 **最近 7 天** 与自动驾驶关键词相关的 **arXiv 论文** 与 **GitHub 仓库**，合并去重后随机展示 10 条，支持「再来 10 篇」。

**Web**

1. 点击侧栏 **「自动驾驶文献」**。
2. **「获取最近自动驾驶领域文章」** → `POST /api/research-feed/start`
3. **「再来 10 篇」** → `POST /api/research-feed/more`（需携带返回的 `sessionId`，30 分钟内有效）

**API**

```bash
# 开始一批
curl -X POST http://localhost:9900/api/research-feed/start

# 再来 10 篇（将 SESSION 换为 start 返回的 sessionId）
curl -X POST http://localhost:9900/api/research-feed/more \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"SESSION"}'
```

可选：在 `application-local.yml` 配置 `research-feed.github-token` 提高 GitHub API 限额。

---

### 腾讯云日志（可选）

对话中若问「查一下某服务最近一个月的日志」，Agent 可调用 **腾讯云 CLS MCP**（`tencent-cls`）。

配置 `MCP_TENCENT_CLS_SSE_ENDPOINT` 或 `application-local.yml` 中对应 `sse-endpoint`；默认查询地域倾向 **`ap-nanjing`**（合肥就近）。未配置时相关能力不可用，AIOps 可仍使用 `queryLogs` 的 Mock 数据。

---

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云大模型 / Embedding / Rerank |
| Milvus | 2.6.10 | 向量数据库 |
| Open-Meteo | - | 天气（免 Key） |
| 高德 MCP | - | 路线 / POI（魔搭 Hosted SSE） |

## 📦 核心模块

```
Auto206Agent/
├── src/main/java/org/example/
│   ├── controller/
│   │   ├── ChatController.java           # 对话 / AIOps SSE
│   │   ├── FileUploadController.java     # 上传向量化
│   │   ├── ServerMonitorController.java  # GET /api/server-monitor/snapshot
│   │   └── ResearchFeedController.java   # 文献聚合 API
│   ├── service/
│   │   ├── ChatService.java              # ReactAgent 主对话
│   │   ├── AiOpsService.java             # AIOps 多 Agent
│   │   ├── ServerMonitorService.java     # Prometheus 即时查询
│   │   └── ResearchFeedService.java      # arXiv + GitHub
│   ├── agent/tool/
│   │   ├── InternalDocsTools.java        # RAG 检索
│   │   ├── ServerMonitorTools.java       # getLabServerRuntimeSnapshot
│   │   ├── QueryMetricsTools.java        # queryPrometheusAlerts
│   │   ├── OpenMeteoWeatherTools.java    # getCityWeatherForecast
│   │   ├── QueryLogsTools.java           # queryLogs
│   │   └── DateTimeTools.java
│   └── config/
├── src/main/resources/
│   ├── static/                           # Web（聊天 / 206监控 / 文献 / AI Ops）
│   └── application.yml
├── aiops-docs/                           # 组内 + 运维知识库（启动索引）
├── data/
│   ├── chat-memory/                      # 会话 JSON
│   ├── rag-session-store/                # 会话级上传副本
│   └── agent-skills/                     # 可选运行时 Skills（.md）
└── application-local.yml.example         # 本地密钥与 MCP（复制后使用）
```

## 📡 核心接口

### 1. 智能问答

**流式对话（推荐）**

```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "组内服务器现在内存占用多少？"
}
```

**普通对话**

```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "合肥明天天气怎么样？"
}
```

### 2. AIOps

```bash
POST /api/ai_ops
Accept: text/event-stream
```

SSE 流式返回《告警分析报告》。

### 3. 206 服务器监控

```bash
GET /api/server-monitor/snapshot
```

### 4. 自动驾驶文献

```bash
POST /api/research-feed/start
POST /api/research-feed/more    # Body: {"sessionId":"..."}
```

### 5. 会话管理

- `POST /api/chat/clear` — 清空指定会话历史
- `GET /api/chat/sessions` — 会话列表
- `GET /api/chat/messages/{sessionId}` — 消息历史
- `GET /api/chat/session/{sessionId}` — 会话元信息

### 6. 文件与向量库

- `POST /api/upload` — 上传并向量化（可选 `sessionId` 表单字段）
- `GET /milvus/health` — Milvus 健康检查

## ⚙️ 核心配置

### 环境变量与本地文件

```bash
# 必填：大模型与向量化
export DASHSCOPE_API_KEY=your-api-key

# 可选：MCP SSE 路径（也可写在 application-local.yml）
export MCP_AMAP_SSE_ENDPOINT=/your-modelscope-id/sse
export MCP_TENCENT_CLS_SSE_ENDPOINT=/sse/your-tencent-cls-endpoint
```

复制 `application-local.yml.example` → `application-local.yml`，填写 DashScope Key 与 MCP `sse-endpoint`（勿提交真实密钥）。

### application.yml 摘要

```yaml
server:
  port: 9900

milvus:
  host: localhost
  port: 19530

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
    mcp:
      client:
        sse:
          connections:
            tencent-cls: { ... }
            amap-maps: { ... }

knowledge:
  indexed-directories:
    - ./aiops-docs
  session-scoped-uploads: true

rag:
  top-k: 3
  hybrid.enabled: true
  rerank.enabled: true
```

### 服务器监控配置

在 Prometheus 的 **Targets** 中确认 `labels.instance`（如 `127.0.0.1:9100`），再配置：

```yaml
prometheus:
  base-url: http://127.0.0.1:9090   # 远程监控可改为 http://172.19.0.64:9090
  mock-enabled: false
  fallback-mock-on-error: true

server-monitor:
  enabled: true
  mock-enabled: false          # true 时不连 Prometheus，返回演示数据
  target-host: 172.19.0.64     # 面板展示用主机名
  instance-regex: "127.0.0.1:9100"
  refresh-seconds: 10
  display-timezone: Asia/Shanghai
```

### AIOps / 日志相关

```yaml
cls:
  mock-enabled: true   # false 且接入真实 CLS 后，queryLogs 才返回线上日志

research-feed:
  github-token: ""     # 可选，提高 GitHub Search 限额
```

## 🚀 快速开始

### 1. 环境准备

```bash
export DASHSCOPE_API_KEY=your-api-key
# 可选：配置 application-local.yml 中的 MCP
```

### 2. 启动应用

**方法一：手动**

```bash
docker compose -f vector-database.yml up -d
mvn clean install
mvn spring-boot:run
```

**方法二：一键（Linux/macOS Makefile）**

```bash
make init   # 启动 Milvus → 启动服务 → 上传 aiops-docs 到向量库
```

### 3. 使用示例

**Web 界面**

```
http://localhost:9900
```

侧栏：**新建对话**、**206 监控**、**自动驾驶文献**；顶部：**AI Ops**；输入框旁：**上传文件**、**图片识图**。

**命令行**

```bash
# 健康检查
curl http://localhost:9900/milvus/health

# 上传全局文档（无 sessionId）
curl -X POST http://localhost:9900/api/upload -F "file=@document.md"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"磁盘快满了怎么办？"}'

# 服务器快照
curl http://localhost:9900/api/server-monitor/snapshot
```

## 💡 常见问题

| 现象 | 处理 |
|------|------|
| 组内文档搜不到 | 确认 Milvus 已启动；`make upload` 或 `knowledge.bootstrap-index-on-startup` |
| 206 监控无数据 | 检查 Prometheus / `instance-regex`；或临时 `server-monitor.mock-enabled: true` |
| 天气正常、路线失败 | 配置 `amap-maps` 的 MCP `sse-endpoint` |
| AIOps 报告无日志证据 | 当前多为 `cls.mock-enabled` 演示数据；需接入 CLS MCP |
| 上传文件其他会话也能看到 | 上传时是否传了 `sessionId`；`knowledge.session-scoped-uploads` 是否为 true |

---

**版本**: v1.0.0  
**作者**: Liu RuiQi  
**许可证**: MIT
