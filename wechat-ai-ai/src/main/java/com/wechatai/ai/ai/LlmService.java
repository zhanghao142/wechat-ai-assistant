package com.wechatai.ai.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.ai.skill.SkillManager;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.common.enums.Channel;
import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import com.wechatai.common.enums.FinishReason;
import com.wechatai.session.entity.MessageEntity;
import com.wechatai.session.mapper.MessageMapper;
import com.wechatai.agent.LlmCaller;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.AgentToolRegistry;
import com.wechatai.tool.registry.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * LLM 调用封装 — 使用 RestClient 直调 DeepSeek API，携带工具清单。
 * <p>
 * 维护消息历史实现 ReAct 循环：
 * 首轮：system + user → LLM 返回 tool_calls
 * 后续：system + user + assistant(tool_calls) + tool_result → LLM 返回最终回复
 */
@Component
public class LlmService implements LlmCaller {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /**
     * 可用音色列表 — CosyVoice v3-plus 全部 4 个系统音色。
     * <p>
     * 来源：阿里云百炼官方文档 + DashScope SDK 源码确认。
     * v3-plus 定位最高质量合成，系统音色数量少于 v3-flash。
     */
    private static final String VOICE_LIST = """

            | 音色名 | 描述 |
            |--------|------|
            | longanyang | 龙安洋（阳光大男孩，男声，20~30岁） |
            | longanhuan | 龙安欢（欢脱元气女，女声，默认，20~30岁） |
            | longhuhu_v3 | 龙呼呼（天真烂漫女童，6~10岁） |
            | longyingmu_v3 | 龙应沐（优雅知性女声，25~30岁） |
            """;

    /** 中央 Orchestrator 提示词 — 只做意图路由，不执行具体工具 */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是"小微"，一个微信智能助手。热情友好，中文交流，保持简洁。

            ## 你的工作方式
            你不需要自己执行任务。根据用户需求选择对应的专家：
            - 日常问答、天气、翻译、上传文档问答、浏览器、邮件、提醒、股票、快递查询、研究报告等 → **generalAgent**
            - 打车、路线、地点搜索、公交地铁骑行步行导航等出行需求 → **taxiAgent**
            - 用户明确要在上传目录读写/列文件时才用 **filesystemAgent**；写研究报告/简报不要交给它
            闲聊时直接回复，不需要调工具。用户问「文档里写了什么」等上传文件内容时，务必交给 generalAgent。
            用户一句话里有多个互不依赖的任务时，可在同一次回复中并行调用多个专家（例如天气+打车各调一次）。

            ## 多步骤研究/报告（强制）
            用户要求联网搜索、读网页、截图、再写成文档/简报时：
            - 只调用一次 generalAgent，把用户完整原话原样放进 task，禁止拆成「先搜」「再读」「再存」多轮
            - 禁止同时并行 generalAgent + filesystemAgent 来完成同一份研究报告
            - 保存报告由 generalAgent 内部用 generateDocument，不要用 filesystemAgent / write_file

            ## 语音朗读（你具备此能力）
            用户要求语音/朗读/播报时：正常写回复正文，末尾另起一行加 [VOICE]。
            系统会合成播放。禁止说「只能文字」「没有语音」「没法出声」。
            触发词：语音回复、语音回答、用语音、读给我听、念出来、播报等。
            示例：
            早上好！今天也要加油。
            [VOICE]
            指定音色用 [VOICE:音色名]，可选：longanyang / longanhuan / longhuhu_v3 / longyingmu_v3

            ## 快递查询规则
            当用户提及快递相关需求时，优先使用 kuaidi100 工具：
            - 用户给了快递单号 → 先用 autoNumber 识别公司，再用 queryTrace 查轨迹
            - 用户给了手机号想查快递 → 用 queryByPhone 返回查询引导
            - 用户问运费 → 用 estimatePrice 估算
            - 顺丰/中通查询需手机号后4位，工具会自动提示
            - **重要**：工具返回的是已经格式化好的中文文本，直接转发给用户即可
            - **禁止**：不要给用户任何查询链接（如官网链接），工具已经能直接查询

            ## 回复红线
            - 不编造数据、不用 Markdown、200 字以内、纯文字不带表情（需要语音时必须加 [VOICE]）
            - 禁止输出任何外部链接或网址
            """;

    // ===== 熔断器：连续异常 → 拉闸，必须重启恢复 =====
    private static final java.util.concurrent.atomic.AtomicInteger consecutive400 = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveError = new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile boolean circuitOpen = false;
    private static final int CIRCUIT_TRIP_400 = 3;        // 连续 3 次 400 → 拉闸
    private static final int CIRCUIT_TRIP_ERROR = 8;      // 连续 8 次其他错误 → 拉闸
    private static final int MESSAGE_BLOAT_THRESHOLD = 150; // 单次消息超 150 条 → 拉闸

    private static final String SYSTEM_PROMPT = SYSTEM_PROMPT_TEMPLATE.replace("{{VOICE_LIST}}", VOICE_LIST);

    @Value("${wechat.ai.llm.api-key}")
    private String apiKey;

    @Value("${wechat.ai.llm.base-url}")
    private String baseUrl;

    @Value("${wechat.ai.llm.model:deepseek-chat}")
    private String modelName;

    private final ToolRegistry toolRegistry;
    private final AgentToolRegistry agentToolRegistry;
    private final MessageMapper messageMapper;
    private final SkillManager skillManager;
    private final UsageMetricsService usageMetricsService;
    private final ObjectMapper objectMapper;
    private RestClient restClient;

    public LlmService(@Lazy ToolRegistry toolRegistry, @Lazy AgentToolRegistry agentToolRegistry,
                      MessageMapper messageMapper, SkillManager skillManager,
                      UsageMetricsService usageMetricsService) {
        this.toolRegistry = toolRegistry;
        this.agentToolRegistry = agentToolRegistry;
        this.messageMapper = messageMapper;
        this.skillManager = skillManager;
        this.usageMetricsService = usageMetricsService;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(120));
        this.restClient = org.springframework.web.client.RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * LLM 推理：生成回复或标记需要调用工具。
     * <p>
     * 从 state 获取 messageHistory（由 SessionPrepareNode 从 MySQL 加载），
     * 确保 system prompt 始终存在，调完 LLM 后自动将消息持久化到 message 表。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> think(ChatGraphState state) {
        long start = System.currentTimeMillis();
        String sessionId = state.getSessionId();
        try {
            // 1. 构建消息列表（从状态取已有历史）
            Map<String, Object> result = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) state.data().get("messageHistory");
            if (messages == null) {
                messages = new ArrayList<>();
            }

            // 确保 system prompt 始终在第一条（DB 中不存 system prompt）
            // 子 Agent 模式：使用 state 传入的自定义 prompt；否则用中央默认 prompt
            String basePrompt = (String) state.data().get(ChatGraphState.KEY_SYSTEM_PROMPT_OVERRIDE);
            if (basePrompt == null) basePrompt = SYSTEM_PROMPT;

            // 文档检索改为 generalAgent 按需调用 searchDocuments，中央不再自动 RAG

            String enrichedPrompt = skillManager.enrich(state, basePrompt);
            if (messages.isEmpty() || !"system".equals(messages.getFirst().get("role"))) {
                messages.addFirst(Map.of("role", "system", "content", enrichedPrompt));
            } else {
                // ReAct 循环中已有 system 消息，替换为带 Skill 的最新版本
                messages.set(0, Map.of("role", "system", "content", enrichedPrompt));
            }

            // 追加当前用户消息（ReAct 循环中最后一条是 tool，跳过）
            // 桌面渠道会先落库再拉历史，末尾可能已是同一条 user，避免重复追加导致 …user,user
            boolean addedUserMsg = false;
            if (!messages.isEmpty() && !"tool".equals(messages.getLast().get("role"))) {
                Map<String, Object> last = messages.getLast();
                boolean alreadyHasCurrentUser = "user".equals(last.get("role"))
                        && java.util.Objects.equals(
                        String.valueOf(last.getOrDefault("content", "")),
                        state.getUserMessage() == null ? "" : state.getUserMessage());
                if (!alreadyHasCurrentUser) {
                    messages.add(Map.of("role", "user", "content", state.getUserMessage()));
                    addedUserMsg = true;
                }
            } else if (messages.isEmpty()) {
                messages.add(Map.of("role", "user", "content", state.getUserMessage()));
                addedUserMsg = true;
            }

            // ====== 消息上限控制：保留最近 MAX_ROUNDS 轮对话 ======
            int MAX_ROUNDS = 30;
            long nonSystemCount = messages.stream()
                    .filter(m -> !"system".equals(m.get("role")))
                    .count();
            if (nonSystemCount > MAX_ROUNDS * 2L) {
                log.warn("消息超上限（{} > {}），开始截断", nonSystemCount, MAX_ROUNDS * 2);
                Map<String, Object> systemMsg = null;
                for (Map<String, Object> m : messages) {
                    if ("system".equals(m.get("role"))) {
                        systemMsg = m;
                        break;
                    }
                }
                List<Map<String, Object>> nonSystem = messages.stream()
                        .filter(m -> !"system".equals(m.get("role")))
                        .collect(java.util.stream.Collectors.toList());
                List<Map<String, Object>> keep = nonSystem.subList(
                        nonSystem.size() - MAX_ROUNDS * 2, nonSystem.size());
                messages = new ArrayList<>();
                if (systemMsg != null) messages.add(systemMsg);
                messages.addAll(keep);
                log.info("截断完成，当前消息数={}", messages.size());

                // 修复截断后 tool 链断裂：找到第一个 user 消息作为起点
                int firstUser = -1;
                for (int i = 0; i < messages.size(); i++) {
                    if ("user".equals(messages.get(i).get("role"))) {
                        firstUser = i;
                        break;
                    }
                }
                if (firstUser > 0) {
                    messages = new ArrayList<>(messages.subList(firstUser, messages.size()));
                    if (systemMsg != null) messages.addFirst(systemMsg);
                } else if (firstUser < 0 && !messages.isEmpty()) {
                    // 全是 tool/assistant 残片，只保留 system 让 LLM 重新开始
                    messages = new ArrayList<>();
                    if (systemMsg != null) messages.add(systemMsg);
                }
            }
            // ====== 消息上限控制结束 ======

            // ====== 修复孤儿 tool_calls：确保没有 assistant(tool_calls) 缺少对应 tool 响应 ======
            messages = fixOrphanedToolCalls(messages);

            // 2. 选工具子集 → 调 LLM
            // 子 Agent 模式：使用 state 传入的工具白名单；否则走中央意图识别
            // 注意：agentTools 可能为空列表（Agent 注册了但 MCP 源没启用），
            // 此时必须用空列表而不是回退到中央 Agent 工具，否则子 Agent 会递归调用自身
            @SuppressWarnings("unchecked")
            List<ToolDefinitionVO> agentTools = (List<ToolDefinitionVO>) state.data()
                    .get(ChatGraphState.KEY_AGENT_TOOLS);
            List<ToolDefinitionVO> toolDefs;
            if (agentTools != null) {
                // KEY_AGENT_TOOLS 显式设置（包括空列表）→ 严格使用白名单，不回退
                if (agentTools.isEmpty()) {
                    log.warn("【Agent】工具白名单为空，LLM 将以无工具模式运行");
                }
                toolDefs = agentTools;
            } else {
                // 中央 Orchestrator 模式 → 意图路由
                toolDefs = selectToolsByIntent(state.getUserMessage());
            }
            Map<String, Object> llmResult = callLlm(messages, toolDefs);
            String content = (String) llmResult.get("content");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCallsList = (List<Map<String, Object>>) llmResult.get("toolCalls");
            long elapsed = (long) llmResult.get("elapsed");

            // 3. 构建 assistant 回复消息
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);

            // 4. 判断是否有工具调用
            boolean hasToolCalls = toolCallsList != null && !toolCallsList.isEmpty();

            if (hasToolCalls) {
                List<String> tcNames = toolCallsList.stream()
                        .map(tc -> (String) ((Map<String, Object>) tc.get("function")).get("name"))
                        .toList();
                log.info("【LLM】返回 {} 个工具调用: {} ({}ms)", toolCallsList.size(), tcNames, elapsed);
                assistantMsg.put("tool_calls", toolCallsList);
                messages.add(assistantMsg);

                // 带 tool_calls 的 assistant 必须落库，否则下一轮还原历史会 400。
                // 但子 Agent 图内部的中间 tool_calls 不能写入父会话，否则会与 taxiAgent 等外层调用缠在一起。
                if (!isAgentSubgraph(state)) {
                    saveAssistantMessage(sessionId, content, toolCallsList, "TOOL", elapsed);
                }
                // user 消息仍遵循渠道规则：桌面由 ChatController 落库，避免重复
                if (shouldPersistToDb(state)) {
                    if (addedUserMsg) saveUserMessage(sessionId, state, elapsed);
                }

                result.put("messageHistory", messages);
                result.put(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS, toolCallsList);
                result.put(ChatGraphState.KEY_NEED_TOOL, true);
                result.put(ChatGraphState.KEY_FINISH_REASON, "TOOL");
            } else {
                log.info("【LLM】回复 ({}ms): {}", elapsed, content);
                messages.add(assistantMsg);

                // 持久化到 MySQL（桌面 REST 由 ChatController 统一落库，避免重复）
                if (shouldPersistToDb(state)) {
                    if (addedUserMsg) saveUserMessage(sessionId, state, elapsed);
                    saveAssistantMessage(sessionId, content, null, "STOP", elapsed);
                }

                result.put("messageHistory", messages);
                result.put(ChatGraphState.KEY_REPLY, content != null ? content : "");
                result.put(ChatGraphState.KEY_NEED_TOOL, false);
                result.put(ChatGraphState.KEY_FINISH_REASON, "STOP");
            }

            return result;

        } catch (Exception e) {
            int cErr = consecutiveError.incrementAndGet();
            log.error("【LLM】调用异常 (连续错误={}/{})", cErr, CIRCUIT_TRIP_ERROR, e);
            if (cErr >= CIRCUIT_TRIP_ERROR) {
                circuitOpen = true;
                log.error("【熔断】连续 {} 次异常，拉闸！仅重启可恢复", cErr);
            }
            return Map.of(
                    ChatGraphState.KEY_REPLY, circuitOpen ? "AI 服务已熔断，请重启应用恢复"
                            : "AI 服务暂时不可用，请稍后重试",
                    ChatGraphState.KEY_NEED_TOOL, false,
                    ChatGraphState.KEY_FINISH_REASON, "ERROR"
            );
        }
    }

    /**
     * 选择合适的工具子集。
     * <p>
     * Agent 工具已注册时 → 始终返回 2 个 Agent 工具，由中央 LLM 自己判断调哪个。
     * Agent 未注册时 → 关键词兜底（向后兼容）。
     */
    private List<ToolDefinitionVO> selectToolsByIntent(String userMessage) {
        // Agent 模式：中央 LLM 只看到 generalAgent + taxiAgent，自己决定调哪个
        List<ToolDefinitionVO> agentTools = agentToolRegistry.getAgentTools();
        if (!agentTools.isEmpty()) {
            List<String> names = agentTools.stream().map(ToolDefinitionVO::getName).toList();
            log.info("🎯 [Agent模式] 中央 LLM 可选 {} 个 Agent 工具: {}", agentTools.size(), names);
            return agentTools;
        }

        // 兜底：关键词匹配（Agent 未注册时）
        if (userMessage == null || userMessage.isEmpty()) {
            return toolRegistry.listTools(null, null);
        }
        if (matchesAny(userMessage,
                "打车", "叫车", "出行", "叫个车", "打个车", "打辆车",
                "导航", "路线", "怎么去", "怎么走", "多远", "多久到",
                "附近", "周边", "周围", "哪里有",
                "订单", "司机", "取消订单", "行程",
                "公交", "地铁", "步行", "骑行", "开车",
                "地图", "定位", "地址")) {
            return agentToolRegistry.getTaxiTools();
        }
        return agentToolRegistry.getGeneralTools();
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 核心 LLM 调用 — 构建请求、调 API、解析响应。
     * <p>
     * 从 {@link #think} 中抽出，供子 Agent ({@code AgentInvoker}) 共用。
     *
     * @param messages 完整消息列表（含 system prompt）
     * @param toolDefs 工具定义子集（由调用方按 Agent 过滤）
     * @return Map of {content, toolCalls, elapsed}
     */
    public Map<String, Object> callLlm(List<Map<String, Object>> messages,
                                        List<ToolDefinitionVO> toolDefs) {
        // === 熔断检查 ===
        if (circuitOpen) {
            return Map.of("content", "AI 服务已熔断，请重启应用恢复", "toolCalls", null,
                    "elapsed", 0L);
        }
        long start = System.currentTimeMillis();

        // 0. 消息膨胀检测
        if (messages.size() > MESSAGE_BLOAT_THRESHOLD) {
            log.error("【熔断】消息数 {} 超阈值 {}，拉闸！",
                    messages.size(), MESSAGE_BLOAT_THRESHOLD);
            circuitOpen = true;
            return Map.of("content", "AI 服务已熔断，请重启应用恢复", "toolCalls", null,
                    "elapsed", 0L);
        }

        // 1. 构建工具 JSON
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinitionVO def : toolDefs) {
            if (!def.isEnabled()) continue;
            tools.add(Map.of("type", "function", "function", Map.of(
                    "name", def.getName(),
                    "description", def.getDescription(),
                    "parameters", def.getParameters()
            )));
        }

        // 2. 构建请求体
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        if (!tools.isEmpty()) {
            requestBody.put("tools", tools);
            requestBody.put("tool_choice", "auto");
        }

        log.info("【LLM】请求 {} model={}, 消息数={}, 工具数={}",
                baseUrl, modelName, messages.size(), tools.size());

        // 3. 调 API
        try {
            String responseBody = restClient.post()
                    .uri(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // 4. 解析响应
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choice = root.path("choices").get(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");

            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("elapsed", elapsed);

            // 成功 → 清零计数器
            consecutive400.set(0);
            consecutiveError.set(0);

            // 5. 工具调用
            boolean hasToolCalls = message.has("tool_calls") && message.get("tool_calls").size() > 0;
            if (hasToolCalls) {
                JsonNode toolCallsNode = message.get("tool_calls");
                List<Map<String, Object>> toolCallsList = new ArrayList<>();
                for (JsonNode tc : toolCallsNode) {
                    toolCallsList.add(Map.of(
                            "id", tc.path("id").asText(),
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.path("function").path("name").asText(),
                                    "arguments", tc.path("function").path("arguments").asText()
                            )
                    ));
                }
                result.put("toolCalls", toolCallsList);
            } else {
                result.put("toolCalls", null);
            }

            // 6. Token / 耗时采集（桌面使用统计）
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);
            result.put("promptTokens", promptTokens);
            result.put("completionTokens", completionTokens);
            result.put("totalTokens", totalTokens);
            usageMetricsService.recordLlmCall(elapsed, promptTokens, completionTokens, totalTokens, hasToolCalls);
            log.info("【LLM】usage prompt={} completion={} total={} ({}ms)",
                    promptTokens, completionTokens, totalTokens, elapsed);

            return result;

        } catch (HttpClientErrorException | JsonProcessingException e) {
            StringBuilder sb = new StringBuilder();
            for (int mi = 0; mi < messages.size(); mi++) {
                Map<String, Object> m = messages.get(mi);
                String role = (String) m.get("role");
                boolean hasTc = m.containsKey("tool_calls") && m.get("tool_calls") != null;
                String tcId = (String) m.get("tool_call_id");
                sb.append("[").append(mi).append("]").append(role)
                  .append(hasTc ? "(tc)" : "")
                  .append(tcId != null ? "(tcid=" + tcId + ")" : "")
                  .append(" ");
            }
            log.error("【LLM】400 错误, 响应: {}, role序列: {}",
                    e instanceof HttpClientErrorException hce ? hce.getResponseBodyAsString() : e.getMessage(), sb);
            int c400 = consecutive400.incrementAndGet();
            log.error("【熔断】连续 400 计数={}/{}", c400, CIRCUIT_TRIP_400);
            if (c400 >= CIRCUIT_TRIP_400) {
                circuitOpen = true;
                log.error("【熔断】连续 {} 次 400，拉闸！仅重启可恢复", c400);
            }
            long elapsed = System.currentTimeMillis() - start;
            Map<String, Object> errResult = new java.util.LinkedHashMap<>();
            errResult.put("content", circuitOpen ? "AI 服务已熔断，请重启应用恢复"
                    : "AI 服务暂时不可用，请稍后重试");
            errResult.put("toolCalls", null);
            errResult.put("elapsed", elapsed);
            return errResult;
        }
    }

    /**
     * 流式 LLM 调用（无工具）。OpenAI 兼容 SSE：逐 token 回调 onDelta。
     *
     * @param onDelta 每个 content 增量；可为 null
     * @return 完整 content（未做业务侧清洗）
     */
    public String callLlmStream(List<Map<String, Object>> messages,
                                java.util.function.Consumer<String> onDelta) {
        if (circuitOpen) {
            String msg = "AI 服务已熔断，请重启应用恢复";
            if (onDelta != null) onDelta.accept(msg);
            return msg;
        }
        long start = System.currentTimeMillis();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);

        log.info("【LLM】流式请求 {} model={}, 消息数={}",
                baseUrl, modelName, messages.size());

        StringBuilder full = new StringBuilder();
        try {
            restClient.post()
                    .uri(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(requestBody)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String errBody = "";
                            try {
                                errBody = new String(response.getBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception ignored) { /* ignore */ }
                            throw new IllegalStateException("LLM 流式 HTTP "
                                    + response.getStatusCode().value() + " " + errBody);
                        }
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody(),
                                        java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) continue;
                                if (!line.startsWith("data:")) continue;
                                String data = line.substring(5).trim();
                                if ("[DONE]".equals(data)) break;
                                JsonNode root = objectMapper.readTree(data);
                                String content = root.path("choices")
                                        .path(0)
                                        .path("delta")
                                        .path("content")
                                        .asText(null);
                                if (content != null && !content.isEmpty()) {
                                    full.append(content);
                                    if (onDelta != null) {
                                        onDelta.accept(content);
                                    }
                                }
                            }
                        }
                        return null;
                    });

            consecutive400.set(0);
            consecutiveError.set(0);
            long elapsed = System.currentTimeMillis() - start;
            log.info("【LLM】流式完成 chars={} ({}ms)", full.length(), elapsed);
            usageMetricsService.recordLlmCall(elapsed, 0, 0, 0, false);
            return full.toString();
        } catch (Exception e) {
            log.error("【LLM】流式失败: {}", e.getMessage());
            consecutiveError.incrementAndGet();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * 桌面渠道由 ChatController 负责最终 user/assistant 落库；
     * 此处再写会导致历史里每条消息重复一份。微信等渠道仍由此处落库。
     */
    private boolean shouldPersistToDb(ChatGraphState state) {
        return state == null || Channel.from(state.getChannel()) != Channel.DESKTOP;
    }

    /**
     * 是否处于子 Agent 图（taxiAgent / generalAgent 等）内部。
     * 子图会设置 KEY_AGENT_TOOLS；其中间 tool 链只应留在内存，不能写入父会话 DB。
     */
    private boolean isAgentSubgraph(ChatGraphState state) {
        return state != null && state.data().get(ChatGraphState.KEY_AGENT_TOOLS) != null;
    }

    /**
     * 保存用户消息到 message 表。
     */
    private void saveUserMessage(String sessionId, ChatGraphState state, long elapsed) {
        if (sessionId == null) return;
        String userMsg = state.getUserMessage();
        if (userMsg == null || userMsg.isEmpty()) return;

        MessageEntity entity = new MessageEntity();
        entity.setMessageId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId);
        entity.setRole(MessageRole.USER);
        entity.setMessageType(MessageType.TEXT);
        entity.setContent(userMsg);
        entity.setLatencyMs((int) elapsed);
        try {
            messageMapper.insert(entity);
        } catch (Exception e) {
            log.warn("保存 user 消息失败: {}", e.getMessage());
        }
    }

    /**
     * 保存 assistant 消息到 message 表。
     */
    private void saveAssistantMessage(String sessionId, String content,
                                       List<Map<String, Object>> toolCalls,
                                       String finishReason, long elapsed) {
        if (sessionId == null) return;

        MessageEntity entity = new MessageEntity();
        entity.setMessageId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId);
        entity.setRole(MessageRole.ASSISTANT);
        entity.setMessageType(toolCalls != null ? MessageType.TOOL_CALL : MessageType.TEXT);
        entity.setContent(content);
        entity.setFinishReason(finishReason);
        entity.setLatencyMs((int) elapsed);
        if (toolCalls != null) {
            try {
                entity.setToolCalls(objectMapper.writeValueAsString(toolCalls));
            } catch (Exception e) {
                log.warn("序列化 tool_calls 失败", e);
            }
        }
        try {
            messageMapper.insert(entity);
        } catch (Exception e) {
            log.warn("保存 assistant 消息失败: {}", e.getMessage());
        }
    }

    /**
     * 修复孤儿 tool_calls：扫描消息列表，确保每个 assistant(tool_calls)
     * 后面都有对应的 tool 响应消息。如果存在不完整的 tool 链（可能由截断或 DB 加载导致），
     * 裁到最后一个完整的 user 消息处重新开始。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fixOrphanedToolCalls(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) return messages;

        // 先处理连续 assistant(tool_calls)：不能合并 ID（后面的 tool 只对应最后一条），
        // 只保留最后一条，丢掉中间无响应的 assistant(tc)。
        messages = collapseConsecutiveAssistantToolCalls(messages);

        int lastCleanUserIdx = -1;
        Set<String> pendingCallIds = new LinkedHashSet<>();

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");

            if ("user".equals(role)) {
                if (pendingCallIds.isEmpty()) {
                    lastCleanUserIdx = i;
                } else {
                    // 有未完成的 tool_calls → 清掉前面所有孤儿 assistant(tc)
                    stripOrphanToolCalls(messages, pendingCallIds);
                }
                pendingCallIds.clear();
            } else if ("assistant".equals(role)) {
                Object tcs = msg.get("tool_calls");
                if (tcs instanceof List) {
                    for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                        Object id = tc.get("id");
                        if (id != null) pendingCallIds.add(id.toString());
                    }
                }
            } else if ("tool".equals(role)) {
                Object tcId = msg.get("tool_call_id");
                if (tcId != null) pendingCallIds.remove(tcId.toString());
            }
        }

        // 末尾检查：如果有未完成的 tool_calls，裁到 lastCleanUserIdx
        if (!pendingCallIds.isEmpty() && lastCleanUserIdx >= 0) {
            Map<String, Object> systemMsg = "system".equals(messages.get(0).get("role"))
                    ? messages.get(0) : null;
            List<Map<String, Object>> cleaned = new ArrayList<>(
                    messages.subList(lastCleanUserIdx, messages.size()));
            if (systemMsg != null && !"system".equals(cleaned.get(0).get("role"))) {
                cleaned.addFirst(systemMsg);
            }
            // 剔除裁后消息中残留的孤儿 tool_calls
            cleaned = stripOrphanToolCalls(cleaned, pendingCallIds);
            log.warn("【tool链修复】检测到孤儿 tool_calls: {}，回退到第 {} 条 user 消息，裁后消息数={}",
                    pendingCallIds, lastCleanUserIdx, cleaned.size());
            return cleaned;
        }

        return messages;
    }

    /**
     * 连续多条 assistant(tool_calls) 时只保留最后一条。
     * 禁止把多条的 tool_calls 数组合并：后续 tool 消息只回应最后一条，合并会导致
     * 「insufficient tool messages following tool_calls」400。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collapseConsecutiveAssistantToolCalls(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            Object tcs = msg.get("tool_calls");
            boolean isAssistantTc = "assistant".equals(msg.get("role"))
                    && tcs instanceof List && !((List<?>) tcs).isEmpty();
            if (!isAssistantTc) {
                out.add(msg);
                continue;
            }

            int j = i + 1;
            while (j < messages.size()) {
                Map<String, Object> next = messages.get(j);
                Object nextTc = next.get("tool_calls");
                boolean nextIsTc = "assistant".equals(next.get("role"))
                        && nextTc instanceof List && !((List<?>) nextTc).isEmpty();
                if (!nextIsTc) break;
                j++;
            }
            if (j > i + 1) {
                log.warn("【tool链修复】丢弃连续 {} 条多余 assistant(tool_calls)，只保留最后一条",
                        j - i - 1);
            }
            out.add(messages.get(j - 1));
            i = j - 1;
        }
        return out;
    }

    /** 从消息列表中剔除没有对应 tool 响应的 tool_calls ID，同时清理孤立 tool 消息 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stripOrphanToolCalls(List<Map<String, Object>> messages,
                                                            Set<String> orphanIds) {
        // 第一步：收集所有合法 assistant(tc) 的 call ID
        Set<String> validCallIds = new HashSet<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) continue;
            Object tcs = msg.get("tool_calls");
            if (!(tcs instanceof List)) continue;
            for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                String id = (String) tc.get("id");
                if (id != null && !orphanIds.contains(id)) validCallIds.add(id);
            }
        }

        // 第二步：删掉 tool_call_id 不匹配任何合法 assistant(tc) 的 tool 消息
        java.util.Iterator<Map<String, Object>> it = messages.iterator();
        while (it.hasNext()) {
            Map<String, Object> msg = it.next();
            if (!"tool".equals(msg.get("role"))) continue;
            String tcId = (String) msg.get("tool_call_id");
            if (tcId != null && !validCallIds.contains(tcId)) {
                it.remove();
                log.warn("【tool链修复】移除孤立 tool 消息: tool_call_id={}", tcId);
            }
        }

        // 第三步：删掉 assistant 消息中属于孤儿 ID 的 tool_calls
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;
            Object tcs = msg.get("tool_calls");
            if (!(tcs instanceof List)) continue;
            List<Map<String, Object>> fixed = new ArrayList<>();
            for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                String id = (String) tc.get("id");
                if (id != null && !orphanIds.contains(id)) {
                    fixed.add(tc);
                }
            }
            if (fixed.size() != ((List) tcs).size()) {
                Map<String, Object> copy = new LinkedHashMap<>(msg);
                copy.put("tool_calls", fixed.isEmpty() ? null : fixed);
                messages.set(i, copy);
            }
        }
        return messages;
    }
}
