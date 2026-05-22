package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.config.ChatMemoryProperties;
import org.example.context.RagRequestContext;
import org.example.dto.ChatImage;
import org.example.dto.ChatImagePart;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.service.ChatSessionPersistenceService;
import org.example.service.ChatSessionPersistenceService.PersistedSession;
import org.example.util.ChatImageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionPersistenceService sessionPersistence;

    @Autowired
    private ChatMemoryProperties chatMemoryProperties;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 存储会话信息
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    /** 单会话保留进模型上下文的对话轮数，见 {@code app.chat.memory.max-message-pairs}（限制在 1～100）。 */
    private int effectiveMaxMessagePairs() {
        int v = chatMemoryProperties != null ? chatMemoryProperties.getMaxMessagePairs() : 20;
        return Math.max(1, Math.min(100, v));
    }

    private static boolean hasChatTextOrImages(ChatRequest request) {
        boolean text = request.getQuestion() != null && !request.getQuestion().trim().isEmpty();
        boolean imgs = request.getImages() != null && !request.getImages().isEmpty();
        return text || imgs;
    }

    /** 持久化与侧栏展示：不存 Base64，仅标出附图数量 */
    private static String userTurnForHistory(String question, int imageCount) {
        String q = question == null ? "" : question.trim();
        String suffix = imageCount > 0 ? " [附图×" + imageCount + "]" : "";
        if (q.isEmpty() && imageCount > 0) {
            return "（图片）" + suffix;
        }
        if (q.isEmpty()) {
            return "（空消息）";
        }
        return q + suffix;
    }

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}, images: {}",
                    request.getId(), request.getQuestion(),
                    request.getImages() == null ? 0 : request.getImages().size());

            if (!hasChatTextOrImages(request)) {
                logger.warn("问题与图片均空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("请输入文字或至少上传一张图片")));
            }

            final List<ChatImage> chatImages;
            try {
                chatImages = ChatImageCodec.decode(request.getImages());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(ex.getMessage())));
            }
            if (request.getImages() != null && !request.getImages().isEmpty() && chatImages.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("图片字段无效或解码后为空")));
            }

            // 获取或创建会话（可从磁盘恢复）
            SessionInfo session = getOrCreateSession(request.getId());
            syncLastUploadedFilenameFromDisk(session);
            RagRequestContext.setSessionId(session.getSessionId());
            try {
                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("会话历史消息对数: {}", history.size() / 2);

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 对话（支持自动工具调用）");

                String rawQuestion = request.getQuestion() != null ? request.getQuestion().trim() : "";
                // 构建系统提示词（包含可选滚动摘要 + 最近若干轮原文 + 轻量意图提示）
                String systemPrompt = chatService.buildSystemPrompt(history, session.getRollingSummary(),
                        rawQuestion.isEmpty() ? null : rawQuestion);

                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt, session.getSessionId());

                String textForPrefetch = (request.getQuestion() != null && !request.getQuestion().trim().isEmpty())
                        ? request.getQuestion().trim()
                        : "请结合图片回答。";
                String questionForAgent = chatService.augmentQuestionWithKnowledgePrefetch(
                        textForPrefetch, session.getSessionId(), history, session.getLastUploadedFilename(),
                        session.getRollingSummary());
                UserMessage userInput = chatService.buildUserInput(questionForAgent, chatImages);
                String fullAnswer = chatService.executeChat(agent, userInput);

                // 更新会话历史；按需滚动摘要压缩后再持久化
                session.addMessage(userTurnForHistory(request.getQuestion(), chatImages.size()), fullAnswer);
                DashScopeChatModel summaryModel = chatService.createSummaryChatModel(dashScopeApi);
                session.runRollingCompressionIfNeeded(chatMemoryProperties, summaryModel, chatService);
                persistSession(session);
                logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                        request.getId(), session.getMessagePairCount());

                return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));
            } finally {
                RagRequestContext.clear();
            }

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            SessionInfo session = resolveSession(request.getId(), false);
            if (session != null) {
                session.clearHistory();
                persistSession(session);
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        if (!hasChatTextOrImages(request)) {
            logger.warn("问题与图片均空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("请输入文字或至少上传一张图片"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        final List<ChatImage> chatImagesForStream;
        try {
            chatImagesForStream = ChatImageCodec.decode(request.getImages());
        } catch (IllegalArgumentException ex) {
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error(ex.getMessage()), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (request.getImages() != null && !request.getImages().isEmpty() && chatImagesForStream.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("图片字段无效或解码后为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        final String ragSid = getOrCreateSession(request.getId()).getSessionId();
        executor.execute(() -> {
            RagRequestContext.setSessionId(ragSid);
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话（与外层一致）
                SessionInfo session = getOrCreateSession(request.getId());
                syncLastUploadedFilenameFromDisk(session);

                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                String rawQuestionStream = request.getQuestion() != null ? request.getQuestion().trim() : "";
                String systemPrompt = chatService.buildSystemPrompt(history, session.getRollingSummary(),
                        rawQuestionStream.isEmpty() ? null : rawQuestionStream);

                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt, session.getSessionId());

                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();

                String textForPrefetch = (request.getQuestion() != null && !request.getQuestion().trim().isEmpty())
                        ? request.getQuestion().trim()
                        : "请结合图片回答。";
                String questionForAgent = chatService.augmentQuestionWithKnowledgePrefetch(
                        textForPrefetch, session.getSessionId(), history, session.getLastUploadedFilename(),
                        session.getRollingSummary());
                UserMessage userInput = chatService.buildUserInput(questionForAgent, chatImagesForStream);
                Flux<NodeOutput> stream = agent.stream(userInput);
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        } finally {
                            RagRequestContext.clear();
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史；按需滚动摘要压缩后再持久化
                            session.addMessage(userTurnForHistory(request.getQuestion(), chatImagesForStream.size()), fullAnswer);
                            DashScopeChatModel summaryModel = chatService.createSummaryChatModel(dashScopeApi);
                            session.runRollingCompressionIfNeeded(chatMemoryProperties, summaryModel, chatService);
                            persistSession(session);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                request.getId(), session.getMessagePairCount());
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        } finally {
                            RagRequestContext.clear();
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                RagRequestContext.clear();
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, 0.3, 8000, 0.9);

                ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 若报告已含一级标题则不再重复加题头（避免与 Markdown 内 # 告警分析报告 重复）
                    if (!finalReportText.stripLeading().startsWith("#")) {
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    }
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    /**
     * 列出磁盘上已持久化的会话（用于前端在 localStorage 丢失后恢复侧边栏）。
     */
    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listPersistedSessions() {
        try {
            List<ChatSessionPersistenceService.SessionListItem> list = sessionPersistence.listSessions();
            Map<String, Object> body = new HashMap<>();
            body.put("sessions", list);
            return ResponseEntity.ok(ApiResponse.success(body));
        } catch (Exception e) {
            logger.error("列出会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取某会话消息：与 Agent 当前使用的「最近原文轮次」一致；若已启用摘要记忆且产生过压缩，另返回 {@code sessionSummary}。
     */
    @GetMapping("/chat/messages/{sessionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSessionMessages(@PathVariable String sessionId) {
        try {
            SessionInfo session = resolveSession(sessionId, false);
            if (session == null) {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }
            Map<String, Object> body = new HashMap<>();
            body.put("sessionId", sessionId);
            body.put("messages", session.getHistory());
            if (session.getRollingSummary() != null && !session.getRollingSummary().isBlank()) {
                body.put("sessionSummary", session.getRollingSummary());
            }
            return ResponseEntity.ok(ApiResponse.success(body));
        } catch (Exception e) {
            logger.error("获取会话消息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 删除会话（内存 + 磁盘）。
     */
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteSession(@PathVariable String sessionId) {
        try {
            sessions.remove(sessionId);
            sessionPersistence.delete(sessionId);
            return ResponseEntity.ok(ApiResponse.success("会话已删除"));
        } catch (Exception e) {
            logger.error("删除会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = resolveSession(sessionId, false);
            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private void persistSession(SessionInfo session) {
        sessionPersistence.save(session.getSessionId(), session.getCreateTime(),
                System.currentTimeMillis(), session.getHistory(), session.getLastUploadedFilename(),
                session.getRollingSummary());
    }

    /** 上传 API 写入磁盘后，内存中的 SessionInfo 需同步最近上传文件名，便于「这篇月报」等指代检索。 */
    private void syncLastUploadedFilenameFromDisk(SessionInfo session) {
        if (session == null) {
            return;
        }
        sessionPersistence.load(session.getSessionId()).ifPresent(p -> {
            String fn = p.getLastUploadedFilename();
            session.setLastUploadedFilename(fn != null && !fn.isBlank() ? fn : null);
        });
    }

    /**
     * 从内存或磁盘解析会话；不创建新会话时若均不存在则返回 null。
     */
    private SessionInfo resolveSession(String sessionId, boolean createIfMissing) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        SessionInfo existing = sessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        Optional<ChatSessionPersistenceService.PersistedSession> disk = sessionPersistence.load(sessionId);
        if (disk.isPresent()) {
            ChatSessionPersistenceService.PersistedSession p = disk.get();
            long ct = p.getCreateTime() > 0 ? p.getCreateTime() : System.currentTimeMillis();
            SessionInfo restored = SessionInfo.restored(sessionId, ct, p, effectiveMaxMessagePairs());
            sessions.put(sessionId, restored);
            return restored;
        }
        if (!createIfMissing) {
            return null;
        }
        SessionInfo created = new SessionInfo(sessionId, effectiveMaxMessagePairs());
        sessions.put(sessionId, created);
        return created;
    }

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            String id = UUID.randomUUID().toString();
            SessionInfo created = new SessionInfo(id, effectiveMaxMessagePairs());
            sessions.put(id, created);
            return created;
        }
        return resolveSession(sessionId, true);
    }

    /** 将若干 user/assistant 消息对格式化为纯文本，供滚动摘要模型阅读。 */
    private static String formatDialogForSummary(List<Map<String, String>> batch) {
        if (batch == null || batch.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < batch.size(); i += 2) {
            String u = batch.get(i).get("content");
            String a = batch.get(i + 1).get("content");
            String us = u == null ? "" : u.strip();
            String as = a == null ? "" : a.strip();
            if (us.length() > 6000) {
                us = us.substring(0, 6000) + "…";
            }
            if (as.length() > 6000) {
                as = as.substring(0, 6000) + "…";
            }
            sb.append("用户：").append(us).append("\n助手：").append(as).append("\n\n");
        }
        return sb.toString().strip();
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息
     * 管理单个会话的历史消息，支持自动清理和线程安全
     */
    private static class SessionInfo {
        private final String sessionId;
        // 存储历史消息对：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        /** 最多保留多少轮（用户+助手）进入上下文，与 {@code app.chat.memory.max-message-pairs} 一致 */
        private final int maxMessagePairs;
        private final ReentrantLock lock;
        /** 本会话最近一次上传的原始文件名（与 ChatSessionPersistenceService 中字段一致） */
        private String lastUploadedFilename;

        /** 早期多轮经模型压缩后的会话摘要；与 messageHistory 中保留的最近原文轮次一并交给模型。 */
        private String rollingSummary;

        private SessionInfo(String sessionId, long createTime, int maxMessagePairs) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = createTime;
            this.maxMessagePairs = Math.max(1, maxMessagePairs);
            this.lock = new ReentrantLock();
        }

        public SessionInfo(String sessionId, int maxMessagePairs) {
            this(sessionId, System.currentTimeMillis(), maxMessagePairs);
        }

        public static SessionInfo restored(String sessionId, long createTime, PersistedSession fromDisk, int maxMessagePairs) {
            SessionInfo s = new SessionInfo(sessionId, createTime, maxMessagePairs);
            s.replaceMessages(fromDisk.getMessages());
            if (fromDisk.getLastUploadedFilename() != null && !fromDisk.getLastUploadedFilename().isBlank()) {
                s.lastUploadedFilename = fromDisk.getLastUploadedFilename();
            }
            if (fromDisk.getRollingSummary() != null && !fromDisk.getRollingSummary().isBlank()) {
                s.rollingSummary = fromDisk.getRollingSummary().strip();
            }
            return s;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getLastUploadedFilename() {
            lock.lock();
            try {
                return lastUploadedFilename;
            } finally {
                lock.unlock();
            }
        }

        public void setLastUploadedFilename(String filename) {
            lock.lock();
            try {
                this.lastUploadedFilename = filename;
            } finally {
                lock.unlock();
            }
        }

        public long getCreateTime() {
            return createTime;
        }

        private void replaceMessages(List<Map<String, String>> fromDisk) {
            if (fromDisk == null) {
                return;
            }
            lock.lock();
            try {
                messageHistory.clear();
                for (Map<String, String> m : fromDisk) {
                    Map<String, String> copy = new LinkedHashMap<>();
                    if (m != null) {
                        if (m.get("role") != null) {
                            copy.put("role", m.get("role"));
                        }
                        if (m.get("content") != null) {
                            copy.put("content", m.get("content"));
                        }
                    }
                    if (!copy.isEmpty()) {
                        messageHistory.add(copy);
                    }
                }
                trimToMaxWindow();
            } finally {
                lock.unlock();
            }
        }

        private void trimToMaxWindow() {
            int maxMessages = maxMessagePairs * 2;
            while (messageHistory.size() > maxMessages) {
                messageHistory.remove(0);
                if (!messageHistory.isEmpty()) {
                    messageHistory.remove(0);
                }
            }
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         * 自动管理历史消息窗口大小
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                trimToMaxWindow();

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                    sessionId, messageHistory.size() / 2);

            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取历史消息（线程安全）
         * 返回副本以避免并发修改
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                this.lastUploadedFilename = null;
                this.rollingSummary = null;
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        public String getRollingSummary() {
            lock.lock();
            try {
                return rollingSummary;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 摘要记忆：当原文轮数超过配置的「最近 K 对」且超出部分不少于 N 对时，将最旧 N 对并入 {@link #rollingSummary} 并从列表移除。
         * 在锁内调用模型，避免与同会话并发读写交错。
         */
        public void runRollingCompressionIfNeeded(ChatMemoryProperties props, DashScopeChatModel summarizerModel,
                                                  ChatService chatService) {
            if (props == null || !props.isSummaryCompressionEnabled() || summarizerModel == null || chatService == null) {
                return;
            }
            int n = Math.max(1, props.getSummaryCompressEveryNPairs());
            int kWanted = Math.max(1, props.getSummaryRecentRawPairs());
            lock.lock();
            try {
                int maxCap = Math.max(1, maxMessagePairs - 1);
                int k = Math.min(kWanted, maxCap);
                int guard = 0;
                while (guard++ < 40) {
                    int pairs = messageHistory.size() / 2;
                    int excess = pairs - k;
                    if (excess < n) {
                        break;
                    }
                    if (messageHistory.size() < n * 2) {
                        break;
                    }
                    List<Map<String, String>> batch = new ArrayList<>(messageHistory.subList(0, n * 2));
                    String dialogPlain = formatDialogForSummary(batch);
                    String merged = chatService.summarizeRollingSegment(summarizerModel, rollingSummary, dialogPlain);
                    if (merged == null || merged.isBlank()) {
                        logger.warn("会话 {} 滚动摘要模型未返回有效内容，停止压缩以免丢失原文", sessionId);
                        break;
                    }
                    rollingSummary = merged;
                    for (int i = 0; i < n * 2; i++) {
                        messageHistory.remove(0);
                    }
                    trimToMaxWindow();
                    logger.info("会话 {} 滚动摘要：已归档 {} 对原文，当前 {} 对、摘要长度 {}",
                            sessionId, n, messageHistory.size() / 2, rollingSummary.length());
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        /**
         * 必须使用小写字段名 + {@code @JsonProperty("Id")}：字段名为 {@code Id} 时，部分 Jackson/Lombok 组合下
         * 客户端传入的 {@code "Id": "session_..."} 无法绑定，服务端会误判为无会话并每次新建 UUID，导致与上传时的 sessionId 不一致、检索不到本会话上传的文档。
         */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID", "sessionId"})
        private String id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String question;

        /**
         * 多模态识图：每项为 MIME + Base64（无 data: 前缀亦可）。
         */
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Images")
        @com.fasterxml.jackson.annotation.JsonAlias({"images"})
        private List<ChatImagePart> images;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID", "sessionId"})
        private String id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
