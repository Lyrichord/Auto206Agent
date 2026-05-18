package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.OpenMeteoWeatherTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.ServerMonitorTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private ServerMonitorTools serverMonitorTools;

    @Autowired
    private OpenMeteoWeatherTools openMeteoWeatherTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        String taskPrompt = "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 提取 Planner 最终输出（包含完整的告警分析报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String reportText = plannerFinalOutput.get().getText();
            String normalized = normalizePlannerReport(reportText);
            logger.info("成功提取到 Planner 最终报告，长度: {}（规范化后: {}）",
                    reportText.length(), normalized.length());
            return Optional.of(normalized);
        } else {
            logger.warn("未能提取到 Planner 最终报告");
            return Optional.empty();
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(DashScopeChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 动态构建方法工具数组（QueryLogsTools 为 Spring Bean 时始终注入；日志内容是否 Mock 由 cls.mock-enabled 控制）
     */
    private Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, serverMonitorTools, openMeteoWeatherTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, serverMonitorTools, openMeteoWeatherTools};
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. **仅当 decision 为 PLAN 或 EXECUTE 时**：本轮回复必须是 **一段合法 JSON**，字段包括 decision、step（下一步说明）、tool（工具名或 none）、context（补充上下文）。不要夹杂 Markdown 或代码块围栏。
                4. **当 decision 为 FINISH 时**：本轮回复 **禁止出现 JSON**（不要输出 `{` 开头的内容）。必须直接输出下面模板要求的 **纯 Markdown**，从 `# 告警分析报告` 第一行开始写。
                5. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-nanjing），若不确定请省略以使用默认值（安徽省合肥市就近为 ap-nanjing）。
                6. 严格禁止编造数据，只能引用工具返回的内容。若某工具返回 success=true 且 message 标明为 Mock、演示或降级数据，应据此撰写分析并在报告中明确标注数据来源，不得视为失败。若连续 3 次调用同一工具仍失败（success=false）或返回空且无说明，需停止该方向并在结论中说明原因。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-nanjing 等）；若 Planner 未给出则使用默认区域 ap-nanjing。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-nanjing 等），或省略 region 以采用默认值。
                6. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，必须终止流程，直接输出"任务无法完成"的报告，明确告知失败原因，严禁凭空编造结果。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }

    /**
     * 将 Planner 误输出的「FINISH JSON」转为可读 Markdown，避免前端只显示原始 JSON。
     */
    static String normalizePlannerReport(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        trimmed = stripMarkdownCodeFence(trimmed);
        if (!trimmed.startsWith("{")) {
            return raw;
        }
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            JsonElement dec = obj.get("decision");
            if (dec == null || !dec.isJsonPrimitive()) {
                return raw;
            }
            String decision = dec.getAsString();
            if (!"FINISH".equalsIgnoreCase(decision)) {
                return raw;
            }
            String step = textOrEmpty(obj, "step");
            String context = textOrEmpty(obj, "context");
            String tool = textOrEmpty(obj, "tool");
            if (step.isEmpty() && context.isEmpty()) {
                return raw;
            }
            return buildFinishMarkdownFromJson(step, context, tool);
        } catch (Exception e) {
            LoggerFactory.getLogger(AiOpsService.class).warn("Planner 输出 JSON 规范化失败，保留原文: {}", e.getMessage());
            return raw;
        }
    }

    /** 去掉 ```json ... ``` 围栏，便于解析模型包了一层代码块的情况 */
    private static String stripMarkdownCodeFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return s;
        }
        String inner = s.substring(firstNl + 1);
        int endFence = inner.lastIndexOf("```");
        if (endFence >= 0) {
            inner = inner.substring(0, endFence);
        }
        return inner.trim();
    }

    private static String textOrEmpty(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return e.getAsString();
    }

    private static String buildFinishMarkdownFromJson(String step, String context, String tool) {
        String toolLine = (tool == null || tool.isBlank() || "none".equalsIgnoreCase(tool))
                ? "（本轮未再调用工具）"
                : "`" + tool + "`";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("# 告警分析报告\n\n---\n\n## 执行摘要\n\n");
        sb.append("自动化排查流程已结束（**FINISH**）。以下为基于当前工具与环境的结论说明。\n\n---\n\n");
        sb.append("## 排查过程与判定\n\n").append(step).append("\n\n---\n\n");
        sb.append("## 详细结论\n\n").append(context).append("\n\n---\n\n");
        sb.append("## 工具与数据可用性\n\n");
        sb.append("- **Planner 记录的工具字段**: ").append(toolLine).append('\n');
        sb.append("- **说明**: 若 Prometheus 不可达、CLS 无告警/无历史、或日志查询处于 Mock/失败状态，");
        sb.append("则无法生成带真实指标与日志证据的根因章节；此时应优先修复可观测性链路，而非推断业务根因。\n\n---\n\n");
        sb.append("## 建议的后续动作\n\n");
        sb.append("1. 检查 `application.yml` 中 **prometheus.base-url** 与网络连通性，确保告警与指标接口可访问。\n");
        sb.append("2. 核对 **CLS** 告警策略、历史与 API 权限；如需本地演示可将 **cls.mock-enabled** 设为 `true` 并确认 Mock 主题与返回内容。\n");
        sb.append("3. 若使用 **MCP** 查询日志，请确认连接、region（默认 `ap-nanjing`，安徽省合肥市就近）与主题名称与线上环境一致。\n\n---\n");
        return sb.toString();
    }
}
