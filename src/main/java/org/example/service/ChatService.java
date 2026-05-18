package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.OpenMeteoWeatherTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.ServerMonitorTools;
import org.example.config.ChatIntentProperties;
import org.example.dto.ChatImage;
import org.example.intent.ChatIntent;
import org.example.intent.SimpleIntentClassifier;
import org.example.skill.ProjectSkillLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    /** 预检索块在用户消息中的固定开头，与系统提示中的说明一致；勿改短以免与正文混淆。 */
    public static final String KNOWLEDGE_PREFETCH_HEADER = "【背景摘录】";

    /** 与 DateTimeTools 默认展示一致，写入系统提示，减少「今天几号」类幻觉。 */
    private static final ZoneId AUTHORITATIVE_CLOCK_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter AUTHORITATIVE_CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss z", Locale.CHINA);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private ServerMonitorTools serverMonitorTools;

    @Autowired
    private ServerMonitorService serverMonitorService;

    @Autowired
    private OpenMeteoWeatherTools openMeteoWeatherTools;

    private final ObjectMapper chatJsonMapper = new ObjectMapper();

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private SimpleIntentClassifier simpleIntentClassifier;

    @Autowired
    private ChatIntentProperties chatIntentProperties;

    @Autowired
    private ProjectSkillLoader projectSkillLoader;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Value("${spring.ai.dashscope.chat.options.model:qwen3-vl-plus}")
    private String dashScopeChatModel;

    @Value("${spring.ai.dashscope.chat.options.max-tokens:4096}")
    private int dashScopeChatMaxTokens;

    /**
     * 通义 VL 多模态对话须走该 path；仍走文本 path 时服务端会返回 {@code InvalidParameter: url error, please check url}。
     * @see <a href="https://help.aliyun.com/zh/model-studio/error-code#error-url">阿里云错误码说明</a>
     */
    private static final String DASHSCOPE_MULTIMODAL_COMPLETIONS_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation";

    /** 当前配置的模型名是否为 DashScope 视觉/多模态（需 multimodal-generation 端点）。 */
    public static boolean isVisionDashScopeModelName(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String m = model.trim().toLowerCase(Locale.ROOT);
        return m.contains("-vl") || m.contains("vl-") || m.contains("qwen-vl");
    }

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        String model = (dashScopeChatModel == null || dashScopeChatModel.isBlank())
                ? DashScopeChatModel.DEFAULT_MODEL_NAME
                : dashScopeChatModel.trim();
        DashScopeApi.Builder b = DashScopeApi.builder().apiKey(dashScopeApiKey);
        if (isVisionDashScopeModelName(model)) {
            b.completionsPath(DASHSCOPE_MULTIMODAL_COMPLETIONS_PATH);
        }
        return b.build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        String model = (dashScopeChatModel == null || dashScopeChatModel.isBlank())
                ? DashScopeChatModel.DEFAULT_MODEL_NAME
                : dashScopeChatModel.trim();
        int maxTok = maxToken > 0 ? maxToken : dashScopeChatMaxTokens;
        var opt = DashScopeChatOptions.builder()
                .withModel(model)
                .withTemperature(temperature)
                .withMaxToken(maxTok)
                .withTopP(topP);
        if (isVisionDashScopeModelName(model)) {
            opt.withMultiModel(true);
        }
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(opt.build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, dashScopeChatMaxTokens, 0.9);
    }

    /** 供 AI Ops 等与主对话共用模型名 */
    public String getDashScopeChatModel() {
        return dashScopeChatModel != null && !dashScopeChatModel.isBlank()
                ? dashScopeChatModel.trim()
                : DashScopeChatModel.DEFAULT_MODEL_NAME;
    }

    /**
     * 构造发给 ReactAgent 的用户消息（支持多图）。无图时等价于纯文本 {@link UserMessage}。
     */
    public UserMessage buildUserInput(String augmentedText, @Nullable List<ChatImage> images) {
        String t = augmentedText == null ? "" : augmentedText;
        if (images == null || images.isEmpty()) {
            return UserMessage.builder().text(t).build();
        }
        if (t.isBlank()) {
            t = "请结合上传的图片回答。";
        }
        UserMessage.Builder b = UserMessage.builder().text(t);
        for (ChatImage img : images) {
            MimeType mt = MimeType.valueOf(img.mimeType());
            Media media = Media.builder()
                    .mimeType(mt)
                    .data(new ByteArrayResource(img.bytes()))
                    .build();
            b.media(media);
        }
        return b.build();
    }

    /**
     * 构建系统提示词（包含历史消息，无滚动摘要）。
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt(history, null, null);
    }

    /**
     * 构建系统提示词（可选「会话摘要」+ 最近若干轮原文）。
     *
     * @param rollingSummary 由摘要记忆压缩得到的早期对话摘要；可为 null
     */
    public String buildSystemPrompt(List<Map<String, String>> history, @Nullable String rollingSummary) {
        return buildSystemPrompt(history, rollingSummary, null);
    }

    /**
     * @param currentUserQuestion 当前用户原始问题（不含预检索前缀）；用于轻量意图提示，可为 null
     */
    public String buildSystemPrompt(List<Map<String, String>> history, @Nullable String rollingSummary,
                                    @Nullable String currentUserQuestion) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        ZonedDateTime now = ZonedDateTime.now(AUTHORITATIVE_CLOCK_ZONE);
        systemPromptBuilder.append("【系统时钟】当前服务器时间（公历唯一权威）：")
                .append(now.format(AUTHORITATIVE_CLOCK_FORMAT))
                .append("。凡「今天/现在/几月几号/星期几/何年何月」须与此及 getCurrentDateTime 工具一致；禁止用检索到的文档示例日期、对话历史中的旧日期或模型记忆冒充当前日期。\n\n");

        if (chatIntentProperties.isEnabled() && currentUserQuestion != null && !currentUserQuestion.isBlank()) {
            ChatIntent intent = simpleIntentClassifier.classify(currentUserQuestion.strip());
            String hint = intent.getSystemPromptHint();
            if (hint != null && !hint.isEmpty()) {
                systemPromptBuilder.append(hint);
            }
            logger.debug("本轮意图: {}", intent);
        }

        // 基础系统提示（Auto206Agent：206 实验室组内知识 + 组内服务器 CPU/GPU 与运维类文档）
        systemPromptBuilder.append("你是 Auto206Agent（206 实验室课题组智能助手）：在**知识库与工具返回可证实**的范围内回答导师与学生的联系方式与方向、论文与项目简介等；也可结合知识库说明组内共用服务器上 CPU、GPU 高占用、内存与磁盘告警、服务不可用、响应变慢等场景的排查与应急处理。**禁止编造**知识库未出现的人名、身份或联系方式。\n");
        systemPromptBuilder.append("默认地区为安徽省合肥市：用户未说明城市时，天气、路线、周边 POI、距离等出行类问题按合肥市处理。\n");
        systemPromptBuilder.append("界面「上传文件」会将文档写入知识库并向量化；用户可在本会话上传 PDF/DOCX/TXT 等后提问，你必须优先依据检索到的正文作答。禁止回答「不支持上传」或类似表述。\n");
        systemPromptBuilder.append("用户消息中若包含图片（多模态输入），须结合图像细节与文字说明作答；不要忽略附图中的文字、图表、界面或公式。\n");
        systemPromptBuilder.append("若用户询问对话过程本身（例如「我刚才问了什么」「对话里最开始说了什么」）：请依据下方「会话摘要」（若有）与「对话历史」最近若干轮原文作答；不要用 queryInternalDocs 返回的运维文档目录或 SOP 标题冒充用户说过的话。摘要由多轮压缩而来，与极细表述可能略有出入，以较近轮次原文为准。\n");
        systemPromptBuilder.append("用户消息可能以「").append(KNOWLEDGE_PREFETCH_HEADER).append("」开头：其后 JSON 的 fragments[].body 为检索到的文档正文摘录，与问题相关时必须据此作答；仅当 body 明显无关时再按常识回答。**若用户问的是某个具体人名是否为本组成员/是谁**，「相关」指正文片段中须**出现该人全名**并与身份描述绑定；仅有「206」「课题组」等泛词而无该人名，不算已证实，不得据此断言其为成员。\n");
        systemPromptBuilder.append("对可能来自 aiops-docs 或本会话上传材料的业务问题，若用户消息未含「").append(KNOWLEDGE_PREFETCH_HEADER).append("」则须先调用 queryInternalDocs 再作答；不得因问题看似生活化就跳过检索。");
        systemPromptBuilder.append("若用户仅询问当前公历日期、星期几、时刻或「今天/现在」指哪一天，**不要**调用 queryInternalDocs（避免把文档里的示例日期当成今天），必须先调用 getCurrentDateTime，且回答须与【系统时钟】及工具返回一致。\n");
        systemPromptBuilder.append("向用户回复时语气自然、像日常对话；正文中禁止出现「根据知识库/根据文档/根据检索/根据上传/资料显示/材料记载/预检索/向量库/内部文档/相关片段/SOP」及含义相近的来源套话，不要解释信息从哪来，直接陈述内容即可。\n");
        systemPromptBuilder.append("运维文档中若出现 company.com、400-xxx 等明显占位邮箱或电话，一律视为模板示例，不得当作真实联系方式；应说明「以下为文档模板，请联系课题组/机房管理员」并引导用户查阅 group 成员表或实际值班渠道。\n");
        systemPromptBuilder.append("校企合作项目（如江淮汽车）知识库表格中：「校内牵头导师」与「对接学生」分列。用户问「对接同学」「谁对接」「驻场学生」等，必须答对接学生列（如刘涛）；勿将导师姓名当作对接同学。问「课题谁负责/导师是谁」可答牵头导师列（如王晓）。\n");
        systemPromptBuilder.append("当用户询问「今天/现在」是几月几号、星期几、当前时刻等时间问题时，必须先调用 getCurrentDateTime，不得凭猜测作答。\n");
        systemPromptBuilder.append("指代消解：当用户使用「他/她/此人/这位/对方」等指代时，必须先结合对话历史中最近一次明确讨论的人或事物来理解；调用 queryInternalDocs 时检索词须写全名或完整主题（禁止仅用「他」等代词作为检索 query）。若预检索或工具返回的正文出现与对话焦点姓名不一致的其他人，视为检索跑偏，须用对话焦点的全名再次调用 queryInternalDocs 后再作答，禁止张冠李戴。\n");
        systemPromptBuilder.append("问及某人年级、硕士/博士阶段、导师、邮箱等时，必须检索 aiops-docs/group/members.md（或 members 表）中有无该人条目；若上文已出现姓名（如论文作者、对话中点名），不得在未对照成员表的情况下回答「知识库没有年级/学历信息」类结论。\n");
        systemPromptBuilder.append("成员称谓与性别（对 members 表所载**全体**成员生效）：介绍导师与学生时，**他/她/性别表述必须与 members.md 中「称谓与性别」说明及各表「性别」列一致**（男→他，女→她）；**禁止**仅凭姓名用字习惯、常见印象或模型记忆推测性别，也**禁止**编造与成员表矛盾的性别或「表中标注为女/男」等说法。若检索片段未含性别列或文首规则，须再检索 members 全文；仍无法确认时用「该同学/该老师」并复述姓名，勿乱用代词。\n");
        systemPromptBuilder.append("人物身份防幻觉：用户问「某某是谁」「某某是不是课题组成员/206的人」等，必须先调用 queryInternalDocs，检索 query 须包含该人**全名**（可附加「成员」「members」「group」以命中成员表）。**仅当**工具或预检索返回的正文片段中能**逐字找到**该姓名，且与导师/学生/研究员等身份在同一表格或同一句语境中关联时，才可陈述其身份；若正文中**没有该姓名**、仅有「206」「课题组」「成员」等泛词或出现的是他人姓名，须明确回答「当前知识库与上传材料中未收录此人，无法确认是否为课题组」，并建议联系管理员核对成员表，**禁止**联想、脑补或凭模型记忆将陌生人写成本组成员。\n");
        systemPromptBuilder.append("当用户询问组内人员、论文成果、项目说明，或任何可能已写入 aiops-docs（含 group 目录）及本会话上传的文档时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查看 **Prometheus 当前触发的告警规则列表**（告警名、描述、firing 状态）时，使用 queryPrometheusAlerts；这与「单台实验室主机此刻 CPU/内存/磁盘/负载/网络」不同——后者须依据用户消息中的【实验室服务器监控快照】JSON，或调用 getLabServerRuntimeSnapshot 拉取与前端「206 服务器监控」同源的即时指标，不得用告警列表冒充主机资源占用。\n");
        systemPromptBuilder.append("用户若问实验室/206/课题组共用服务器「现在怎么样、内存/硬盘/CPU/负载高不高、盘满了怎么办」等：必须结合快照或 getLabServerRuntimeSnapshot 的数值与 queryInternalDocs 命中的 aiops-docs 处置文档（如 disk_high_usage、memory_high_usage、cpu_high_usage）作答；数值以快照为准，处置步骤以文档为准。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询，默认查询地域 ap-nanjing（安徽省合肥市就近），查询时间范围为近一个月。\n");
        systemPromptBuilder.append("天气类问题（气温、阴晴雨雪、风、空气质量、几日预报、「今天天气」等）：必须先调用 getCityWeatherForecast，仅根据该工具返回的 JSON（current、daily 等）向用户转述；**禁止**在未调用该工具（或高德 MCP 天气类工具且已成功返回）时写出具体℃、风力、空气质量等数字，禁止凭记忆编造天气。\n");
        systemPromptBuilder.append("驾车/步行/骑行/公交路线、地点或关键词/周边 POI、测距、导航打车或专属地图等：调用魔搭 Hosted SSE 的高德 MCP（连接名 amap-maps）所注册工具；未指定城市时与地理位置相关的默认按安徽省合肥市理解。\n\n");

        if (rollingSummary != null && !rollingSummary.isBlank()) {
            systemPromptBuilder.append("--- 会话摘要（早期多轮对话已压缩）---\n")
                    .append(rollingSummary.strip())
                    .append("\n--- 会话摘要结束 ---\n\n");
        }

        String skillsSection = projectSkillLoader.getSkillsPromptSection();
        if (skillsSection != null && !skillsSection.isBlank()) {
            systemPromptBuilder.append(skillsSection);
        }

        // 添加历史消息（最近 K 轮原文）
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史（最近若干轮原文）---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请基于以上会话摘要（若有）与对话历史，回答用户的新问题。");

        return systemPromptBuilder.toString();
    }

    /**
     * 供摘要记忆使用：较低温度、较短输出，避免与主对话同额 max_tokens。
     */
    public DashScopeChatModel createSummaryChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.2, 1536, 0.9);
    }

    private static final String ROLLING_SUMMARY_SYSTEM = """
            你是对话记忆归档助手，将用户与助手之间的若干轮原文压缩为可复用的中文要点摘要。
            要求：第三人称、按时间顺序、保留关键专名（人名、论文名、系统名等）与结论；不编造未在原文出现的事实；不写标题与客套话；输出为一段连续正文。""";

    /**
     * 将一段早期对话并入已有会话摘要（首次则 existingSummary 为空）。
     *
     * @return 合并后的摘要；失败时返回 null（调用方应中止压缩循环以免丢消息）
     */
    @Nullable
    public String summarizeRollingSegment(DashScopeChatModel model, @Nullable String existingSummary, String dialogBatch) {
        if (model == null || dialogBatch == null || dialogBatch.isBlank()) {
            return null;
        }
        String ex = existingSummary == null ? "" : existingSummary.strip();
        if (ex.length() > 8000) {
            ex = "…（更早摘要已节略）\n" + ex.substring(ex.length() - 8000);
        }
        String userPayload = "【已有会话摘要】\n"
                + (ex.isEmpty() ? "（尚无，请根据下方对话生成首版摘要。）" : ex)
                + "\n\n【待并入本轮的早期对话】\n"
                + dialogBatch
                + "\n\n请输出**合并后的一段**中文会话摘要正文（仅一段，不要分条标题）。";
        try {
            String out = model.call(new SystemMessage(ROLLING_SUMMARY_SYSTEM), new UserMessage(userPayload));
            if (out == null || out.isBlank()) {
                return null;
            }
            String s = out.strip();
            if (s.length() > 16000) {
                s = s.substring(0, 16000) + "\n…（摘要过长已截断）";
            }
            return s;
        } catch (Exception e) {
            logger.warn("滚动会话摘要模型调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        return buildMethodToolsArray(null);
    }

    /**
     * @param chatSessionIdForRag 非空时注入 queryInternalDocs 的 Milvus 会话过滤（流式工具线程无 ThreadLocal）
     */
    public Object[] buildMethodToolsArray(@Nullable String chatSessionIdForRag) {
        InternalDocsTools ragTools =
                chatSessionIdForRag != null && !chatSessionIdForRag.isBlank()
                        ? internalDocsTools.scopedToChatSession(chatSessionIdForRag)
                        : internalDocsTools;
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, ragTools, queryMetricsTools, serverMonitorTools, openMeteoWeatherTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, ragTools, queryMetricsTools, serverMonitorTools, openMeteoWeatherTools};
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return createReactAgent(chatModel, systemPrompt, null);
    }

    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt,
                                       @Nullable String chatSessionIdForRag) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray(chatSessionIdForRag))
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 在交给 Agent 之前对当前问题做一次向量检索，并把命中片段拼进用户消息，避免模型把问题误判为「常识题」而从不调 queryInternalDocs。
     * 若未命中则原样返回问题。
     */
    public String augmentQuestionWithKnowledgePrefetch(String question, String chatSessionId,
                                                       List<Map<String, String>> history, String lastUploadedFilename) {
        return augmentQuestionWithKnowledgePrefetch(question, chatSessionId, history, lastUploadedFilename, null);
    }

    /**
     * 同 {@link #augmentQuestionWithKnowledgePrefetch(String, String, List, String)}，可选附带滚动会话摘要以改善指代类追问的向量 query。
     */
    public String augmentQuestionWithKnowledgePrefetch(String question, String chatSessionId,
                                                       List<Map<String, String>> history, String lastUploadedFilename,
                                                       @Nullable String rollingSummary) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String trimmed = question.trim();
        if (trimmed.startsWith(KNOWLEDGE_PREFETCH_HEADER)) {
            return question;
        }
        if (isMetaConversationQuestion(trimmed)) {
            logger.debug("跳过知识库预检索：对话元问题（应依赖消息历史，不宜注入运维文档摘录）");
            return question;
        }
        if (chatIntentProperties.isEnabled() && chatIntentProperties.isSkipPrefetchForToolFirstIntents()) {
            ChatIntent intent = simpleIntentClassifier.classify(trimmed);
            if (intent == ChatIntent.TIME_DATE || intent == ChatIntent.WEATHER || intent == ChatIntent.MAP_NAVIGATION) {
                logger.debug("跳过知识库预检索：工具优先意图 {}", intent);
                return question;
            }
        }
        try {
            boolean serverRuntime = looksLikeLabServerRuntimeQuestion(trimmed);
            String vectorQuery = buildVectorSearchQueryForPrefetch(trimmed, history, lastUploadedFilename, rollingSummary);
            if (serverRuntime) {
                vectorQuery = labServerPrefetchRagBoost() + "\n" + vectorQuery;
            }
            if (!vectorQuery.equals(trimmed)) {
                logger.info("RAG 预检索已拼接对话上文以消解指代，扩展后 query 长度约 {}", vectorQuery.length());
            }
            String raw = internalDocsTools.retrieveInternalDocsJsonForPrefetch(vectorQuery, chatSessionId);
            boolean ragHit = raw != null && !isKnowledgePrefetchEmpty(raw);

            if (serverRuntime) {
                String snapBlock = formatLabServerSnapshotForPrefetch();
                StringBuilder sb = new StringBuilder();
                sb.append(snapBlock).append("\n\n");
                if (ragHit) {
                    sb.append(KNOWLEDGE_PREFETCH_HEADER)
                            .append("下列 JSON 中 fragments 数组每条含 body（文档正文摘录）与 metadata；请依据 body 作答。\n")
                            .append("请仅用其中事实核对后作答；若无关可忽略。对用户输出时不要提及本段或「知识库」「检索」等来源套话。\n")
                            .append(raw);
                } else {
                    sb.append("【运维文档预检索】未命中向量片段；回答处置步骤时须再调用 queryInternalDocs，")
                            .append("query 建议包含：磁盘满、内存高、CPU 高、实验室服务器、disk_high_usage、memory_high_usage、cpu_high_usage、lab_server_snapshot_and_actions。");
                }
                sb.append("\n\n---\n用户问题：\n").append(trimmed);
                logger.info("实验室服务器类问题：已注入监控快照，RAG 命中={}", ragHit);
                return sb.toString();
            }

            if (!ragHit) {
                logger.debug("背景预检索无有效命中，不注入上下文");
                return question;
            }
            logger.info("背景预检索已注入用户上下文，命中片段长度约: {}", raw.length());
            return KNOWLEDGE_PREFETCH_HEADER
                    + "下列 JSON 中 fragments 数组每条含 body（文档正文摘录）与 metadata；请依据 body 作答。\n"
                    + "请仅用其中事实核对后作答；若无关可忽略。对用户输出时不要提及本段或「知识库」「检索」等来源套话。\n"
                    + raw
                    + "\n\n---\n用户问题：\n"
                    + trimmed;
        } catch (Exception e) {
            logger.warn("背景预检索失败，降级为原问题: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 识别「实验室/206 主机此刻资源与处置」类自然语言问法（非仅点击前端监控按钮），用于注入 Prometheus 快照并强化 RAG。
     */
    static boolean looksLikeLabServerRuntimeQuestion(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        String s = q.strip();
        String low = s.toLowerCase(Locale.ROOT);

        if (low.contains("game server") || low.contains("minecraft") || low.contains("steam")) {
            return false;
        }

        boolean labCtx = s.contains("实验室") || s.contains("206") || s.contains("课题组") || s.contains("组内")
                || s.contains("共用") && s.contains("服务器") || s.contains("机房");
        boolean serverWord = s.contains("服务器") || s.contains("主机") || s.contains("机器")
                || s.contains("节点") || low.contains("prometheus") || low.contains("node exporter")
                || low.contains("node_exporter");
        boolean metric = s.contains("内存") || s.contains("磁盘") || s.contains("硬盘") || s.contains("根分区")
                || s.contains("CPU") || s.contains("cpu") || s.contains("负载") || s.contains("网络")
                || s.contains("占用") || s.contains("监控") || s.contains("指标") || s.contains("运行情况");
        boolean situation = s.contains("情况") || s.contains("怎样") || s.contains("如何") || s.contains("怎么样")
                || s.contains("现在") || s.contains("目前") || s.contains("实时") || s.contains("高不高")
                || s.contains("满了") || s.contains("太满") || s.contains("告警") || s.contains("爆满")
                || s.contains("怎么办") || s.contains("该如何") || s.contains("处理") || s.contains("排查")
                || s.contains("解决方案") || s.contains("应急");

        if (labCtx && serverWord) {
            return true;
        }
        if (serverWord && (metric || situation)) {
            return true;
        }
        // 无「服务器」一词但明显在问资源：如「内存占用如何」「硬盘太满」
        if ((s.contains("内存") && (s.contains("占用") || situation))
                || ((s.contains("磁盘") || s.contains("硬盘")) && (s.contains("满") || situation || s.contains("容量")))) {
            return true;
        }
        if (low.contains("lab server") || low.contains("server memory") || low.contains("disk full")
                || low.contains("disk usage") || low.contains("memory usage")) {
            return true;
        }
        return false;
    }

    /** 预检索 query 前缀：拉高 aiops-docs 中服务器监控与处置类文档召回。 */
    static String labServerPrefetchRagBoost() {
        return "206 实验室 共用服务器 Prometheus node_exporter 监控快照 cpuPercent memoryPercent diskRootUsage "
                + "磁盘满 内存高 CPU 高 负载 应急 处理方案 aiops-docs disk_high_usage memory_high_usage cpu_high_usage "
                + "gpu_high_usage lab_server_snapshot_and_actions";
    }

    private String formatLabServerSnapshotForPrefetch() {
        try {
            Map<String, Object> snap = serverMonitorService.snapshot();
            String json = chatJsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snap);
            return "【实验室服务器监控快照】与前端「206 服务器监控」按钮同源（Prometheus 即时查询）。"
                    + "下列 JSON 为当前主机汇总；回答「现在占用多少」须引用其中数值；若 ok=false 或某字段带 Error 须如实说明，勿编造。\n"
                    + json;
        } catch (JsonProcessingException e) {
            logger.warn("监控快照序列化失败: {}", e.getMessage());
            return "【实验室服务器监控快照】拉取失败：" + e.getMessage();
        }
    }

    private static boolean isKnowledgePrefetchEmpty(String toolOutput) {
        String s = toolOutput.strip();
        return s.contains("\"status\": \"no_results\"") || s.contains("\"status\":\"no_results\"")
                || s.contains("\"status\": \"error\"") || s.contains("\"status\":\"error\"");
    }

    /**
     * 用户追问「我刚才问了什么」等：应靠对话历史回答；预检索若命中全局运维 SOP，模型会误把摘录当「用户说过的话」。
     */
    static boolean isMetaConversationQuestion(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        String s = q.strip();
        if (s.length() > 120) {
            return false;
        }
        if (s.contains("再往上")) {
            return true;
        }
        if (s.contains("这个对话") || s.contains("本对话") || s.contains("这轮对话")) {
            return true;
        }
        if (s.contains("问了什么") || s.contains("问过什么") || s.contains("说过什么") || s.contains("聊了什么")) {
            return true;
        }
        if (s.contains("第一个提问") || s.contains("第一个问题") || s.contains("最开始问")) {
            return true;
        }
        if (s.contains("你怎么知道") || s.contains("怎么知道的")) {
            return true;
        }
        if (s.contains("不记得") || s.contains("还记得吗")) {
            return true;
        }
        if (s.contains("刚才") && (s.contains("问") || s.contains("说") || s.contains("聊"))) {
            return true;
        }
        if (s.contains("上一句") || s.contains("上个问题")) {
            return true;
        }
        return false;
    }

    /**
     * 向量预检索用的查询串：对「他/她」等省略主语的追问，拼接最近对话，避免 embedding 与「成员表」中无关人名错误对齐。
     */
    static String buildVectorSearchQueryForPrefetch(String question, List<Map<String, String>> history,
                                                    String lastUploadedFilename) {
        return buildVectorSearchQueryForPrefetch(question, history, lastUploadedFilename, null);
    }

    static String buildVectorSearchQueryForPrefetch(String question, List<Map<String, String>> history,
                                                    String lastUploadedFilename, @Nullable String rollingSummary) {
        String tail = "";
        if (history != null && !history.isEmpty() && needsDialogTailAppendedForRag(question)) {
            tail = extractRecentDialogTailForRag(history, 700);
        }
        if (rollingSummary != null && !rollingSummary.isBlank()) {
            String sc = rollingSummary.strip();
            if (sc.length() > 900) {
                sc = "…\n" + sc.substring(sc.length() - 900);
            }
            if (tail.isBlank()) {
                tail = "【会话摘要】\n" + sc;
            } else {
                tail = "【会话摘要】\n" + sc + "\n【对话上文】\n" + tail;
            }
            if (tail.length() > 1600) {
                tail = tail.substring(tail.length() - 1600);
            }
        }
        String base;
        if (!tail.isBlank()) {
            base = "【对话上文】\n" + tail + "\n【当前追问】\n" + question;
        } else {
            base = question;
        }
        String withUpload = prependSessionUploadHintIfNeeded(question, lastUploadedFilename, base);
        return prependShxiaoProjectRoleHintsIfNeeded(question, prependMemberDirectoryHintsIfNeeded(question, tail, withUpload));
    }

    /** 用户用「这篇月报」等指代且本会话曾上传文件时，把文件名拼进向量查询，避免检索漂移。 */
    static String prependSessionUploadHintIfNeeded(String question, String lastUploadedFilename, String vectorQuery) {
        if (lastUploadedFilename == null || lastUploadedFilename.isBlank()) {
            return vectorQuery;
        }
        if (!needsVagueDocumentPointer(question) && !looksLikeSessionDocSummaryQuestion(question)) {
            return vectorQuery;
        }
        return "【本会话最近上传文件】" + lastUploadedFilename.strip() + "\n" + vectorQuery;
    }

    static boolean needsVagueDocumentPointer(String question) {
        if (question == null) {
            return false;
        }
        String s = question.strip();
        if (s.length() > 48) {
            return false;
        }
        return s.contains("这篇") || s.contains("该篇") || s.contains("此篇") || s.contains("本文")
                || s.contains("这份") || s.contains("该文件") || s.contains("此文件") || s.contains("这个文件")
                || s.contains("这个文档") || s.contains("刚才上传") || s.contains("刚上传") || s.contains("上面上传")
                || s.contains("上传的") || s.contains("月报") || s.contains("周报") || s.contains("附件");
    }

    /** 短问句概括上传材料，仍无显式文件名时依赖 lastUploadedFilename。 */
    static boolean looksLikeSessionDocSummaryQuestion(String question) {
        if (question == null) {
            return false;
        }
        String s = question.strip();
        if (s.length() > 36) {
            return false;
        }
        return (s.contains("内容") || s.contains("主要") || s.contains("概括") || s.contains("总结")
                || s.contains("讲了") || s.contains("说什么") || s.contains("是啥"))
                && (s.contains("文档") || s.contains("材料") || s.contains("报告") || s.contains("文件"));
    }

    /** 校企项目角色：强化检索 query，减少「对接同学」与「导师/负责人」混淆。 */
    static String prependShxiaoProjectRoleHintsIfNeeded(String question, String vectorQuery) {
        if (question == null || question.isBlank()) {
            return vectorQuery;
        }
        String s = question.strip();
        boolean jhContext = s.contains("江淮") || s.contains("校企") || s.contains("无人物流") || s.contains("行人轨迹")
                || s.contains("月报") || s.contains("这个项目") || s.contains("该校企");
        boolean dockAsk = s.contains("对接") && (s.contains("同学") || s.contains("学生") || s.contains("谁"));
        if (!jhContext && !dockAsk) {
            return vectorQuery;
        }
        if (dockAsk) {
            return "查 aiops-docs/group/projects.md：江淮汽车项目「对接学生」为刘涛，勿将导师当作对接同学。\n" + vectorQuery;
        }
        if (s.contains("负责人") || s.contains("谁负责") || (s.contains("导师") && s.contains("谁"))) {
            return "校企项目：校内牵头导师一般为王晓；日常对接学生为刘涛。见 projects.md 表头分列。\n" + vectorQuery;
        }
        return vectorQuery;
    }

    /**
     * 当用户在问「年级/邮箱/导师/基本信息」等成员字段时，向检索串注入「课题组成员名录 + 人名」提示，
     * 减弱 projects.md 里校企段落对向量召回的挤压（例如只写了对接学生时的追问）。
     */
    static String prependMemberDirectoryHintsIfNeeded(String question, String dialogTail, String vectorQuery) {
        if (question == null || !questionLooksLikeLabMemberFieldQuery(question)) {
            return vectorQuery;
        }
        String hints = extractPersonNameHintsForRag(question, dialogTail);
        if (!hints.isBlank()) {
            return "课题组成员名录 members.md 人物表：" + hints + " 年级 邮箱 导师 研究方向\n" + vectorQuery;
        }
        // 已判定为成员类问题但未能从问句解析出人名：仍注入 members 提示（依赖对话上文块中的姓名）
        if (needsDialogTailAppendedForRag(question) && dialogTail != null && !dialogTail.isBlank()) {
            return "课题组成员名录 members.md（年级/硕士博士/导师/邮箱）\n" + vectorQuery;
        }
        return vectorQuery;
    }

    /** 是否像在问组内人员档案字段（而非纯项目/论文标题）。 */
    static boolean questionLooksLikeLabMemberFieldQuery(String question) {
        String s = question.strip();
        if (s.length() > 96) {
            return false;
        }
        boolean field = s.contains("年级") || s.contains("哪一级") || s.contains("几级") || s.contains("几几级")
                || s.contains("邮箱") || s.contains("导师")
                || s.contains("研究方向") || s.contains("基本信息") || s.contains("人员信息") || s.contains("联系方式")
                || s.contains("办公室") || (s.contains("研究生") && s.contains("级"))
                || (s.contains("级") && (s.contains("硕士") || s.contains("博士") || s.contains("学士") || s.contains("博士后")))
                // 「有什么信息 / 他有什么信息」等：依赖代词或「某某他…」结构，避免纯项目文档抢召回
                || (s.contains("信息") && needsDialogTailAppendedForRag(s))
                || Pattern.compile("[\\u4e00-\\u9fff]{2,4}[他她].*信息").matcher(s).find();
        if (!field) {
            return false;
        }
        // 纯「信息」且无语境：避免误把泛问当成员档案
        if (s.contains("信息") && !needsDialogTailAppendedForRag(s)
                && !Pattern.compile("[\\u4e00-\\u9fff]{2,4}[他她].*信息").matcher(s).find()
                && !s.contains("年级") && !s.contains("邮箱") && !s.contains("导师") && !s.contains("基本")) {
            return false;
        }
        return true;
    }

    /** 从当前问句与对话尾部抽取可能的人物姓名，供向量检索拼接。 */
    static String extractPersonNameHintsForRag(String question, String dialogTail) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String q = question == null ? "" : question.strip();
        String t = dialogTail == null ? "" : dialogTail;

        Pattern pNameThenTa = Pattern.compile("^([\\u4e00-\\u9fff]{2,4})[他她]");
        Matcher m1 = pNameThenTa.matcher(q);
        if (m1.find()) {
            names.add(m1.group(1));
        }
        Pattern pDock = Pattern.compile("对接学生[：:\\s]*([\\u4e00-\\u9fff]{2,4})");
        String lastDock = lastMatchGroup(pDock, t);
        if (lastDock != null) {
            names.add(lastDock);
        }
        Pattern pTongXue = Pattern.compile("([\\u4e00-\\u9fff]{2,4})同学");
        String lastTx = lastMatchGroup(pTongXue, t);
        if (lastTx != null) {
            names.add(lastTx);
        }
        Pattern pInQ = Pattern.compile("([\\u4e00-\\u9fff]{2,4})同学");
        Matcher m2 = pInQ.matcher(q);
        while (m2.find()) {
            names.add(m2.group(1));
        }
        // 上文助手句常见：「方可发表了…」「作者：方可」「认识，方可…」
        for (Pattern p : Arrays.asList(
                Pattern.compile("([\\u4e00-\\u9fff]{2,4})发表了"),
                Pattern.compile("([\\u4e00-\\u9fff]{2,4})的会议论文"),
                Pattern.compile("作者[为：:\\s]*([\\u4e00-\\u9fff]{2,4})"),
                Pattern.compile("认识[,，\\s]+([\\u4e00-\\u9fff]{2,4})"),
                Pattern.compile("([\\u4e00-\\u9fff]{2,4})同学是"))) {
            Matcher mx = p.matcher(t);
            while (mx.find()) {
                names.add(mx.group(1));
            }
        }
        names.removeIf(ChatService::isTrivialNameToken);
        return String.join(" ", names);
    }

    private static String lastMatchGroup(Pattern p, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = p.matcher(text);
        String g = null;
        while (m.find()) {
            g = m.group(1);
        }
        return g;
    }

    /** 去掉明显非人名的双字等（保守）。 */
    private static boolean isTrivialNameToken(String s) {
        if (s == null || s.length() < 2 || s.length() > 4) {
            return true;
        }
        return "我们".equals(s) || "什么".equals(s) || "目前".equals(s) || "状态".equals(s) || "合作".equals(s)
                || "项目".equals(s) || "主题".equals(s) || "会议".equals(s) || "基金".equals(s) || "国家级".equals(s)
                || "校企".equals(s) || "在研".equals(s) || "方法".equals(s) || "研究".equals(s);
    }

    /** 判断当前问句是否很可能依赖上文主语（仅保守触发，减少误拼接）。 */
    static boolean needsDialogTailAppendedForRag(String question) {
        if (question == null) {
            return false;
        }
        String s = question.strip();
        if (s.length() > 72) {
            return false;
        }
        String withoutOther = s.replace("其他", "").replace("其它", "");
        if (withoutOther.contains("他") || withoutOther.contains("她")) {
            return true;
        }
        if (s.contains("他们") || s.contains("她们") || s.contains("它们")) {
            return true;
        }
        return s.contains("此人") || s.contains("其人") || s.contains("这位") || s.contains("那位")
                || s.contains("这人") || s.contains("那人") || s.contains("对方")
                || s.contains("上面说的") || s.contains("刚才说的") || s.contains("之前说的")
                || s.contains("那个人") || s.contains("这个人");
    }

    /** 取最近若干条对话，供 RAG 查询拼接（不含当前轮）。 */
    private static String extractRecentDialogTailForRag(List<Map<String, String>> history, int maxChars) {
        int n = history.size();
        int start = Math.max(0, n - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < n; i++) {
            Map<String, String> m = history.get(i);
            if (m == null) {
                continue;
            }
            String role = m.get("role");
            String content = m.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String line = content.strip();
            if (line.length() > 480) {
                line = line.substring(0, 480) + "…";
            }
            if ("user".equals(role)) {
                sb.append("用户：").append(line).append('\n');
            } else if ("assistant".equals(role)) {
                sb.append("助手：").append(line).append('\n');
            }
        }
        String out = sb.toString().strip();
        if (out.length() <= maxChars) {
            return out;
        }
        return out.substring(out.length() - maxChars);
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        return executeChat(agent, UserMessage.builder().text(question == null ? "" : question).build());
    }

    /**
     * 执行 ReactAgent 对话（非流式，支持多模态 {@link UserMessage}）。
     */
    public String executeChat(ReactAgent agent, UserMessage userMessage) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call(UserMessage) - 自动处理工具调用");
        var response = agent.call(userMessage);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
