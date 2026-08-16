package com.wechatai.ai.agent;

import com.wechatai.agent.AgentResult;
import com.wechatai.ai.ai.LlmService;
import com.wechatai.ai.ai.RunWatchdog;
import com.wechatai.ai.graph.GraphInterruptHandler;
import com.wechatai.ai.node.LlmThinkNode;
import com.wechatai.ai.node.PrepareInputNode;
import com.wechatai.ai.node.SessionPrepareNode;
import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.ai.node.ToolExecuteNode;
import com.wechatai.ai.service.DesktopProgressEmitter;
import com.wechatai.ai.state.ChatGraphState;
import org.springframework.context.annotation.Lazy;
import com.wechatai.proactive.path.BehaviorPathService;
import com.wechatai.proactive.path.BehaviorPathService.PathDecision;
import com.wechatai.session.mapper.MessageMapper;
import com.wechatai.session.service.SessionService;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.AgentToolRegistry;
import com.wechatai.tool.registry.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 子 Agent 专用 ReAct 图执行器。
 * <p>
 * 自建独立的 LangGraph 图（复用同一套节点实现，但单独编译、无图中断）。
 * 所有子 Agent 共用此图实例，每次调用传入不同 system prompt + 工具白名单。
 */
@Component
public class AgentGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphRunner.class);

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final AgentToolRegistry agentToolRegistry;
    private final SessionService sessionService;
    private final MessageMapper messageMapper;
    private final GraphInterruptHandler interruptHandler;
    private final RunWatchdog watchdog;
    private final BehaviorPathService behaviorPathService;
    private final UsageMetricsService usageMetricsService;
    private final ParallelAgentBatchService parallelBatchService;
    private final DesktopProgressEmitter progressEmitter;

    private CompiledGraph<ChatGraphState> compiledGraph;
    private final AtomicLong threadIdGen = new AtomicLong(System.currentTimeMillis());

    /** threadId → 挂起上下文（WRITE 工具等待确认） */
    private final ConcurrentHashMap<String, PendingConfirm> pendingConfirms = new ConcurrentHashMap<>();

    @Value("${mcp.filesystem.allowed-dir:./upload}")
    private String sandboxDir;

    @Value("${docgen.storage-path:./AItext}")
    private String docgenDir;

    public AgentGraphRunner(LlmService llmService, ToolRegistry toolRegistry,
                            AgentToolRegistry agentToolRegistry,
                            SessionService sessionService, MessageMapper messageMapper,
                            GraphInterruptHandler interruptHandler,
                            RunWatchdog watchdog,
                            BehaviorPathService behaviorPathService,
                            UsageMetricsService usageMetricsService,
                            @Lazy ParallelAgentBatchService parallelBatchService,
                            DesktopProgressEmitter progressEmitter) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.agentToolRegistry = agentToolRegistry;
        this.sessionService = sessionService;
        this.messageMapper = messageMapper;
        this.interruptHandler = interruptHandler;
        this.watchdog = watchdog;
        this.behaviorPathService = behaviorPathService;
        this.usageMetricsService = usageMetricsService;
        this.parallelBatchService = parallelBatchService;
        this.progressEmitter = progressEmitter;
    }

    @PostConstruct
    public void init() throws Exception {
        PrepareInputNode prepareInputNode = new PrepareInputNode();
        SessionPrepareNode sessionPrepareNode = new SessionPrepareNode(sessionService, messageMapper);
        LlmThinkNode llmThinkNode = new LlmThinkNode(llmService);
        ToolExecuteNode toolExecuteNode = new ToolExecuteNode(
                toolRegistry, agentToolRegistry, this, messageMapper, watchdog, sandboxDir,
                docgenDir, usageMetricsService, parallelBatchService, progressEmitter);

        this.compiledGraph = new StateGraph<>(ChatGraphState::new)
                .addNode("prepare_input", prepareInputNode)
                .addNode("session_prepare", sessionPrepareNode)
                .addNode("llm_think", llmThinkNode)
                .addNode("tool_execute", toolExecuteNode)
                .addEdge(START, "prepare_input")
                .addEdge("prepare_input", "session_prepare")
                .addEdge("session_prepare", "llm_think")
                .addConditionalEdges("llm_think",
                        state -> CompletableFuture.completedFuture(
                                state.isNeedTool() ? "tool" : "done"),
                        Map.of("tool", "tool_execute", "done", END))
                .addEdge("tool_execute", "llm_think")
                .compile(CompileConfig.builder()
                        .interruptBefore("tool_execute")
                        .checkpointSaver(new MemorySaver())
                        .build());

        log.info("✅ AgentGraphRunner 编译完成（子 Agent 共用图，interruptBefore=tool_execute）");
    }

    public AgentResult run(String agentId, String task, String userId,
                           List<ToolDefinitionVO> tools,
                           int maxRounds, long timeoutMs) {
        return run(agentId, task, userId, tools, maxRounds, timeoutMs, 0, null, null, null);
    }

    /**
     * 带递归深度追踪的子 Agent 执行。
     *
     * @param parentDepth 父 Agent 的深度（0 = 中央 Orchestrator 直接调用）
     * @param parentAgent 父 Agent 名称（null = 中央 Orchestrator）
     * @param sessionId   会话 ID（注入给 WRITE 工具如 setRemind）
     * @param channel     渠道 WECHAT/DESKTOP
     */
    public AgentResult run(String agentId, String task, String userId,
                           List<ToolDefinitionVO> tools,
                           int maxRounds, long timeoutMs,
                           int parentDepth, String parentAgent,
                           String sessionId, String channel) {
        long start = System.currentTimeMillis();
        int currentDepth = parentDepth + 1;

        // 无底层工具时直接失败，避免 LLM 空跑并幻觉「已完成」
        if (tools == null || tools.isEmpty()) {
            String err = agentId + " 当前无可用工具（对应 MCP/能力未启用或连接失败），无法执行: "
                    + (task != null && task.length() > 80 ? task.substring(0, 80) + "..." : task);
            log.warn("【AgentGraph】{}", err);
            return AgentResult.fail(err, 0, 0);
        }

        // ── 行为路径自学习：RAG 检索相似历史任务 ──
        PathDecision pathDecision = behaviorPathService.retrieveAndDecide(task, userId, null);
        boolean hasHotPath = pathDecision.isHotPath() && pathDecision.suggestedToolChain() != null;

        try {
            String systemPrompt = buildAgentSystemPrompt(agentId, tools);

            // 热路径：在 system prompt 末尾注入建议的工具链，LLM 只需填参
            if (hasHotPath) {
                systemPrompt += "\n\n## 行为路径提示\n"
                        + "以下是历史相似任务使用的工具链，请优先参考：\n"
                        + pathDecision.suggestedToolChain() + "\n"
                        + "如果当前场景匹配，直接使用相同工具并填入合适参数即可。";
            }

            // Critic 规则注入（仅当 RAG 匹配到路径且该路径绑定了规则时才注入）
            if (pathDecision.hasCriticRules()) {
                systemPrompt += "\n\n## 经验规则\n" + pathDecision.criticRules();
            }

            Map<String, Object> initData = new java.util.LinkedHashMap<>();
            initData.put(ChatGraphState.KEY_USER_ID, userId);
            initData.put(ChatGraphState.KEY_USER_MESSAGE, task);
            initData.put(ChatGraphState.KEY_SYSTEM_PROMPT_OVERRIDE, systemPrompt);
            initData.put(ChatGraphState.KEY_AGENT_TOOLS, tools);
            if (sessionId != null) {
                initData.put(ChatGraphState.KEY_SESSION_ID, sessionId);
            }
            if (channel != null) {
                initData.put(ChatGraphState.KEY_CHANNEL, channel);
            }
            // 递归保护：记录当前 Agent 名和深度，供 ToolExecuteNode 检测递归调用
            initData.put("__current_agent__", agentId);
            initData.put("__agent_depth__", currentDepth);
            initData.put("messageHistory", new java.util.ArrayList<>(List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", task)
            )));
            // 携带 trace 信息供图内节点使用
            if (pathDecision.trace() != null) {
                initData.put("__path_trace_id__", pathDecision.trace().getTraceId());
            }

            String threadId = "agent-" + agentId + "-" + threadIdGen.incrementAndGet();
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            // === 图中断循环：invoke → 检查是否有待执行工具 → 自动恢复 ===
            List<Map<String, Object>> recordedToolCalls = new ArrayList<>();
            // 整体包超时壳：正常完成原样返回，超时抛 TimeoutException
            return watchdog.runWithTimeout(() -> {
                var result = compiledGraph.invoke(initData, config);
                int resumeCount = 0;
                while (result.isPresent() && resumeCount < 50) {
                    ChatGraphState state = result.get();
                    if (!"TOOL".equals(state.getFinishReason())) {
                        String reply = state.getReply();
                        if (reply != null && !reply.isEmpty()) {
                            long elapsed = System.currentTimeMillis() - start;
                            log.info("【AgentGraph】{} 完成 ({}ms, 中断恢复{}次): {}", agentId, elapsed, resumeCount,
                                    reply.length() > 150 ? reply.substring(0, 150) + "..." : reply);

                            // ── 录制工具链（异步：冷启动 embed 不挡用户回复）──
                            if (pathDecision.trace() != null) {
                                behaviorPathService.recordAfterAgentAsync(
                                        pathDecision.trace(), recordedToolCalls,
                                        reply, resumeCount + 1, elapsed);
                            }

                            return AgentResult.ok(reply, 0, elapsed, state.getToolAttachments());
                        }
                        break;
                    }
                    // 有待执行工具 → 检测 WRITE 类型
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                            state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                    // 录制本轮工具调用
                    if (toolCalls != null) {
                        recordedToolCalls.addAll(toolCalls);
                    }
                    String writeTool = interruptHandler.findWriteToolName(toolCalls);
                    if (writeTool != null) {
                        // WRITE 工具 → 挂起等待确认，返回确认请求给调用方
                        String args = extractArgs(toolCalls, writeTool);
                        String confirmMsg = interruptHandler.buildConfirmMessage(writeTool, args);
                        String tId = config.threadId().orElse(threadId);
                        pendingConfirms.put(tId, new PendingConfirm(agentId, tId, writeTool));
                        long elapsed = System.currentTimeMillis() - start;
                        log.info("【AgentGraph】{} WRITE工具 {} 等待确认, threadId={}", agentId, writeTool, tId);
                        return AgentResult.confirm(tId, writeTool, confirmMsg, 0, elapsed);
                    }
                    // 纯 READ → 自动恢复
                    result = compiledGraph.invoke((Map<String, Object>) null, config);
                    resumeCount++;
                }

                long elapsed = System.currentTimeMillis() - start;
                return AgentResult.fail("子 Agent 未返回结果（恢复" + resumeCount + "次）", 0, elapsed);
            }, timeoutMs, agentId);

        } catch (TimeoutException e) {
            log.warn("【AgentGraph】{} 超时 ({}ms)", agentId, timeoutMs);
            return AgentResult.fail("子Agent超时(" + timeoutMs + "ms)", 0,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("【AgentGraph】{} 图执行异常", agentId, e);
            return AgentResult.fail(e.getMessage(), 0, System.currentTimeMillis() - start);
        }
    }

    /** generalAgent 专用提示词模板 */
    private static final String GENERAL_AGENT_PROMPT = """
            你是"小微"，一个微信智能助手。热情友好，中文交流，简洁回复。

            ## 回复格式规范

            ### 语音输出标记（你具备此能力）
            系统支持语音朗读：在回复正文后另起一行加 [VOICE]，后台会合成并播放。
            用户要求语音/朗读/播报时，必须照做，禁止说「只能文字」「没有语音」「没法出声」。
            触发词示例：读给我听、读出来、念给我听、说给我听、用嘴说、语音回复、语音回答、用语音、讲出来、用说的、播报
            正确示例：
            早上好！新的一天加油。
            [VOICE]

            ### 音色切换
            - 用户指定音色：回复末尾加 [VOICE:音色名]，可用音色：longanyang / longanhuan / longhuhu_v3 / longyingmu_v3
            - 本次对话切换：加 [VOICE_SWITCH:音色名]
            - 永久切换：加 [VOICE_SET:音色名]

            ## 浏览器截图规则
            - screenshot 是加分项，不是必须；没有截图也可以完成研究报告
            - screenshot 默认只截可视区域（fullPage=false）；除非用户明确要求整页，不要传 fullPage=true
            - navigate 或 screenshot 失败/超时：立刻停止浏览器操作，禁止再次调用 screenshot 或换 URL 重试截图
            - 失败后直接用已有 readUrl 材料写报告并 generateDocument，回复里说明「截图未成功已跳过」即可
            - screenshot 成功后系统已自动发图给用户，直接说"已经帮你截好了"即可
            - 绝对不要说"我没法发图片"、"你无法看到截图"

            ## 浏览器操作工作流程
            1. navigate 打开网页
            2. getPageElements 获取可交互元素（必须，不要跳过）
            3. click/fill 用返回的 selector，优先用 text 参数，严禁裸标签选择器
            4. getText 提取内容或 screenshot 确认

            ## 多步骤研究任务（在本 Agent 内一次做完）
            用户要求「搜索→阅读→（可选截图）→写报告」时，在本轮内完成，不要中途只交半截结果：
            1. searchInternet 找来源
            2. readUrl 打开 2～3 个可靠链接提取数据（文字材料足够即可写报告）
            3. 截图可选：最多尝试 1 次 navigate + 1 次 screenshot；失败/超时立即跳过，禁止重试
            4. 若报告含 ≥2 个可比数值：先 renderChart（bar/line/pie）拿到 `![...](url)`，再写入正文；无可靠数据则跳过插图
            5. 用 generateDocument 保存报告（优先 .md）；不要用 write_file / 虚构 /data 路径
            6. 最后简要汇总；不要编造数字；缺截图/缺图不影响交报告

            ## 上传文档问答
            - 用户问上传文档/合同/PDF/笔记里的内容时，必须先调用 searchDocuments，再根据工具结果回答
            - 不要编造文档内容；未命中时如实说明并可建议换关键词或确认已上传

            ## 联网搜索与打开网页（强制）
            - 需要事实/数据/新闻/研究报告时：先 searchInternet（单次已合并多引擎），再 readUrl 或浏览器 navigate
            - 【少搜多读】同一任务 searchInternet 一般 1～2 次；已有 ≥3 条真实「链接」时优先 readUrl，禁止同义反复搜索
            - readUrl / navigate 的地址必须逐字来自 searchInternet 结果里的「链接」，禁止编造或拼接 URL
            - 结果链接明显跑题才换关键词再搜；不要自己猜 gov.cn / 气象网 / 百科路径
            - 搜不到或打不开：如实写明缺来源，禁止用臆造数字或假链接凑结论
            - 多步骤研究：searchInternet → readUrl×2～3 →（可选 renderChart）→ generateDocument，不要中途空转搜索

            ## 保存报告与插图
            - 研究报告/简报/总结成文件 → 只用 generateDocument；有可比数据时用 renderChart 插图
            - renderChart：多对象同指标→bar，多时期→line，占比且类目≤6→pie；禁止手搓图表 URL、禁止编造数值
            - 一张简报通常 1～2 张图；把工具返回的图片 Markdown 行原样写入 content
            - write_file 仅当用户明确要求写到沙箱某路径时才用
            ## 文档/搜索回复格式
            - 找到内容：📄 找到了 [N] 处相关内容：> [引用片段] 来源：[文件名]
            - 未找到：😅 抱歉，我还没有学习到这个内容。你可以试试上传相关文档让我学习。
            - 不可用：😅 抱歉，这个功能暂时不可用，请稍后再试。

            ## 回复红线
            - 不编造数据、不用 Markdown、200 字以内、纯文字不带表情（[VOICE] 标记除外，需要时必须加）
            - 不要啰嗦复述图片内容（用户没问就别列【文字】【描述】【类型】），一句话带过
            - 收到图片只发图不提问时，简单说说内容然后问需要什么帮助
            - 不要声称没有截图/语音/发文件能力（有对应标记或工具时按规范做）
            """;

    /** filesystemAgent 专用提示词模板 */
    private static final String FILESYSTEM_AGENT_PROMPT = """
            你是"小微"的文件管理助手，负责读写用户上传目录下的文件。
            文件路径使用相对路径即可（如 111.txt），系统会自动放到正确位置。

            ## 工作方式
            - 用户说"保存xxx" → 直接用 write_file 写入，不要先问确认
            - 用户说"读一下xxx" → 直接用 read_file 读取
            - 用户说"看看有哪些文件" → 直接用 list_directory 列出
            - 用户说"新建文件夹" → 直接用 create_directory 创建
            - 所有工具直接调用，系统会自动处理安全确认

            ## 回复格式
            - 读文件：直接展示内容
            - 写文件：简短确认已保存；另起一行写 [FILE:相对或绝对路径]（系统会展示可点击附件）
            - 列出目录：简单清单
            - 中文，200 字以内
            """;

    /** jobAgent 专用提示词模板 */
    private static final String JOB_AGENT_PROMPT = """
            你是"小微"的求职助手，负责简历管理、职位搜索和投递。

            ## 工作方式
            - 用户问简历 → 调 my-resume，把返回的 JSON 原样输出
            - 用户搜职位 → 调 user-search-job，按用户要求填筛选条件
            - 用户要投递 → 先确认职位，再调 user-apply-job
            - 投递时直接调 user-apply-job，系统会自动确认，不要再额外问

            ## 回复格式（重要！）
            - 工具返回什么就输出什么，不要美化、不要总结、不要翻译
            - 职位列表原样展示 salary、location、company、jobName 等关键字段
            - 不要输出无意义的范文和客套话，200 字以内
            """;

    /** mcdonaldsAgent 专用提示词模板 */
    private static final String MCDONALDS_AGENT_PROMPT = """
            你是"小微"的麦当劳助手，负责点餐、配送、优惠券、积分商城、活动日历等所有麦当劳相关服务。

            ## 工作方式
            - 用户想点餐 → 先调 query-nearby-stores 或 delivery-query-stores 查门店 → 调 query-meals 看菜单 → 调 query-meal-detail 看详情 → 调 calculate-price 算价 → 调 create-order 下单
            - 用户想外卖配送 → 先调 delivery-query-addresses 看地址 → 没地址调 delivery-create-address 新建 → 调 delivery-query-stores 查可配送门店 → 下单
            - 用户问优惠券 → 调 available-coupons 查可领券 / query-store-coupons 查门店可用券 / query-my-coupons 查已领券
            - 用户要领券 → 调 auto-bind-coupons 一键领券（系统会确认）
            - 用户问积分 → 调 query-my-account 查积分 → mall-points-products 看兑换列表 → mall-create-order 兑换
            - 用户问活动 → 调 campaign-calendar 查营销日历
            - 用户问营养 → 调 list-nutrition-foods 查餐品营养信息
            - 下单/兑换等写操作系统会自动确认

            ## 回复格式
            - 菜单：分类列出，每项标价格
            - 优惠券：券名、优惠金额、有效期
            - 订单：确认商品、金额、配送方式
            - 中文，200 字以内
            """;

    /** taxiAgent 专用提示词模板 */
    private static final String TAXI_AGENT_PROMPT = """
            你是"小微"的出行助手，专注打车、路线规划、地点搜索。

            ## 工作方式
            - 用户提到目的地 → 先用 maps_textsearch 查起终点坐标
            - 用户要估价 → 用 taxi_estimate 列出车型和价格
            - 用户要下单 → 先确认起终点和车型，再调 taxi_create_order
            - 用户要查路线 → 根据出行方式选 maps_direction_driving/transit/walking/bicycling
            - 用户问"附近有什么" → 先用 maps_regeocode 或 maps_place_around

            ## 回复格式
            - 列出车型价格时用简洁清单，每行一个
            - 路线规划结果只报关键信息（距离、时间、主要路段）
            - 中文，200 字以内
            """;

    /** 是否仍有挂起的 WRITE 确认（用于剔除历史消息里残留的 needsConfirm） */
    public boolean hasPendingConfirm(String threadId) {
        return threadId != null && pendingConfirms.containsKey(threadId);
    }

    /**
     * 确认执行挂起的 WRITE 工具，恢复子 Agent 图执行。
     *
     * @param threadId  挂起时返回的 threadId
     * @param confirmed true=确认执行, false=取消
     * @return 子 Agent 最终结果
     */
    public AgentResult confirmResume(String threadId, boolean confirmed) {
        PendingConfirm pc = pendingConfirms.remove(threadId);
        if (pc == null) {
            return AgentResult.fail("未找到挂起的确认请求: " + threadId, 0, 0);
        }

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        if (!confirmed) {
            // 取消：注入取消结果到 checkpoint
            try {
                var snapshot = compiledGraph.getState(config);
                if (snapshot != null) {
                    ChatGraphState state = snapshot.state();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages = (List<Map<String, Object>>)
                            state.data().get("messageHistory");
                    if (messages != null) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                                state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                        if (toolCalls != null) {
                            for (Map<String, Object> tc : toolCalls) {
                                String id = (String) tc.get("id");
                                java.util.LinkedHashMap<String, Object> cancelMsg = new java.util.LinkedHashMap<>();
                                cancelMsg.put("role", "tool");
                                cancelMsg.put("tool_call_id", id);
                                cancelMsg.put("content", "用户已取消此操作");
                                messages.add(cancelMsg);
                            }
                        }
                        Map<String, Object> updates = new java.util.HashMap<>();
                        updates.put("messageHistory", messages);
                        updates.put(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS, List.of());
                        updates.put(ChatGraphState.KEY_NEED_TOOL, false);
                        compiledGraph.updateState(config, updates);
                    }
                }
            } catch (Exception e) {
                log.error("【AgentGraph】取消注入失败", e);
            }
            log.info("【AgentGraph】{} 用户取消 WRITE 工具 {}", pc.agentId, pc.toolName);
            return AgentResult.ok("用户已取消" + pc.toolName + "操作", 0, 0);
        }

        // 确认：恢复图执行，循环处理可能的后续 WRITE
        try {
            var result = compiledGraph.invoke((Map<String, Object>) null, config);
            int resumeCount = 0;
            while (result.isPresent() && resumeCount < 50) {
                ChatGraphState state = result.get();
                if (!"TOOL".equals(state.getFinishReason())) {
                    String reply = state.getReply();
                    if (reply != null && !reply.isEmpty()) {
                        return AgentResult.ok(reply, 0, 0, state.getToolAttachments());
                    }
                    break;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                        state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                String writeTool = interruptHandler.findWriteToolName(toolCalls);
                if (writeTool != null) {
                    // 再次遇到 WRITE → 再次挂起
                    String args = extractArgs(toolCalls, writeTool);
                    String confirmMsg = interruptHandler.buildConfirmMessage(writeTool, args);
                    pendingConfirms.put(threadId, new PendingConfirm(pc.agentId, threadId, writeTool));
                    log.info("【AgentGraph】{} 再次遇到 WRITE {} 等待确认", pc.agentId, writeTool);
                    return AgentResult.confirm(threadId, writeTool, confirmMsg, 0, 0);
                }
                result = compiledGraph.invoke((Map<String, Object>) null, config);
                resumeCount++;
            }
            return AgentResult.ok("操作完成", 0, 0);
        } catch (Exception e) {
            log.error("【AgentGraph】确认恢复失败", e);
            return AgentResult.fail(e.getMessage(), 0, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractArgs(List<Map<String, Object>> toolCalls, String toolName) {
        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            if (func != null && toolName.equals(func.get("name"))) {
                String args = (String) func.get("arguments");
                return args != null ? args : "{}";
            }
        }
        return "{}";
    }

    /** WRITE 工具挂起上下文 */
    private record PendingConfirm(String agentId, String threadId, String toolName) {}

    private String buildAgentSystemPrompt(String agentId, List<ToolDefinitionVO> tools) {
        String basePrompt = switch (agentId) {
            case "generalAgent" -> GENERAL_AGENT_PROMPT;
            case "taxiAgent" -> TAXI_AGENT_PROMPT;
            case "filesystemAgent" -> FILESYSTEM_AGENT_PROMPT;
            case "jobAgent" -> JOB_AGENT_PROMPT;
            case "mcdonaldsAgent" -> MCDONALDS_AGENT_PROMPT;
            default -> "你是" + agentId + "，专注于处理特定领域的任务。返回简洁的结果摘要。\n";
        };

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n## 可用工具\n");
        for (ToolDefinitionVO t : tools) {
            sb.append("- ").append(t.getName()).append("：")
                    .append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
