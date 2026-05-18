---
name: auto206agent
description: >-
  Guides work on the Auto206Agent (206 lab) Spring Boot repo: Spring AI Alibaba DashScope chat,
  ReactAgent with tools + MCP, Milvus RAG with hybrid retrieval and rerank, session-scoped uploads
  and ThreadLocal/Reactor-safe RAG filtering, chat memory JSON persistence, optional rolling summary,
  lightweight rule-based chat intent. Use when editing Java/services/controllers, RAG/Milvus,
  session isolation, application.yml, or Vue chat/upload flows tied to this backend.
---

# Auto206Agent 项目 Skill

## 技术栈与入口

- **后端**：Spring Boot，`spring-ai-alibaba` DashScope ChatModel，`ReactAgent`（`ChatService`）。
- **对话 HTTP**：`ChatController` — `/chat`、`/chat_stream`（SSE）；会话键为请求体 **`Id`**（兼容 `id` / `sessionId`），勿随意改 `@JsonProperty` 绑定，否则上传与对话 session 不一致。
- **向量**：Milvus；索引 `VectorIndexService`；检索与混合召回 `VectorSearchService`；工具侧 `InternalDocsTools`。
- **前端**：Vue 3（若改上传/会话 ID，需与后端 `Id` 字段约定一致）。

## 会话隔离（必守）

1. **元数据**：全局文档 `_kb_scope=global`；会话上传 `_kb_scope=session` 且 `metadata["_session_id"]` 为上传时的 sessionId（`VectorIndexService.buildMetadata`）。
2. **检索过滤**：`VectorSearchService.buildSessionVisibilityExpr` — 仅 `global` 或「session 且 `_session_id` 匹配」；**不要**放行 `_kb_scope` 为 null 的旧数据，避免跨会话泄漏。
3. **HTTP 线程**：`RagRequestContext`（ThreadLocal）在 `ChatController` 请求入口 `setSessionId`，`finally` 里 `clear()`。
4. **流式 / Reactor 工具线程**：**不能**只靠 ThreadLocal。`createReactAgent(..., chatSessionId)` 通过 `internalDocsTools.scopedToChatSession(chatSessionId)` 注入 **`boundRagSessionId`**，`queryInternalDocs` 内 `resolveSessionIdForFilter()` 优先用绑定值。
5. **预检索**：`augmentQuestionWithKnowledgePrefetch` 必须 **显式传入** `chatSessionId`（`retrieveInternalDocsJsonForPrefetch`），与执行线程解耦。
6. **上传路径**：带 `sessionId` 的上传应写入 **`session-rag-store`**（见 `knowledge.session-rag-store-dir`），**不要**把会话文件镜像进 `indexed-directories` 的全量启动索引路径，否则会被打成 global。

## RAG 与工具

- **分片**：`DocumentChunkService` — Markdown 标题 → 段落 → `document.chunk.max-size` / `overlap`。
- **混合检索 + Rerank**：`rag.hybrid.*`、`rag.rerank.*`；检索实现以 `VectorSearchService` 为准。
- **天气**：`OpenMeteoWeatherTools`（`@Tool` 直连 Open-Meteo HTTP），**不是** `application.yml` 里配置的 MCP SSE。
- **MCP**（`spring.ai.mcp.client.sse.connections`）：如 `tencent-cls`、`amap-maps`；与本地 `@Tool` 并存。

## 对话记忆与压缩

- **持久化**：`ChatSessionPersistenceService` → `app.chat.memory.directory` 下每会话一个 JSON。
- **窗口**：`max-message-pairs`；**滚动摘要**：`summary-compression-enabled` 等为 true 时，`SessionInfo.runRollingCompressionIfNeeded` 调 `ChatService.summarizeRollingSegment`。
- **意图**：`SimpleIntentClassifier` + `app.chat.intent.*`；仅影响系统提示与可选跳过 RAG 预检索，**不改变** Milvus 会话过滤。

## 修改时的检查清单

- 改检索/过滤：同步检查 **`explicitSessionId` / `boundRagSessionId` / `RagRequestContext`** 三条路径是否仍一致。
- 改上传/镜像目录：对照 `KnowledgeProperties`、`FileUploadController` 与会话泄漏注释。
- 改工具注册：`ChatService.buildMethodToolsArray(chatSessionIdForRag)` 与 `ReactAgent.builder().methodTools(...)`。

## 常用路径

| 主题 | 主要文件 |
|------|----------|
| 运行时 Skills（注入系统提示） | `app.skills.*`、`ProjectSkillLoader.java`、`data/agent-skills/*.md` |
| 对话与 Agent | `ChatService.java`, `ChatController.java` |
| RAG 工具 | `InternalDocsTools.java`, `VectorSearchService.java` |
| 索引入库 | `VectorIndexService.java`, `DocumentChunkService.java` |
| 会话持久化 | `ChatSessionPersistenceService.java` |
| 请求上下文 | `RagRequestContext.java` |
| 意图 | `intent/SimpleIntentClassifier.java`, `ChatIntentProperties.java` |
| 配置 | `src/main/resources/application.yml` |

## 构建

- 根目录：`mvn compile` / `mvn test`（Windows PowerShell 用 `;` 连接命令）。
