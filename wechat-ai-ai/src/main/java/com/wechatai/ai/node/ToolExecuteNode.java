package com.wechatai.ai.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.ai.agent.AgentGraphRunner;
import com.wechatai.ai.agent.ParallelAgentBatchService;
import com.wechatai.ai.agent.ParallelAgentBatchService.Batch;
import com.wechatai.ai.agent.ParallelAgentBatchService.ConfirmItem;
import com.wechatai.ai.agent.ParallelAgentBatchService.SlotResult;
import com.wechatai.ai.ai.RunWatchdog;
import com.wechatai.agent.AgentResult;
import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.ai.service.DesktopProgressEmitter;
import com.wechatai.ai.service.ToolProgressTexts;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.ai.util.RoutePayloadExtractor;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import com.wechatai.session.entity.MessageEntity;
import com.wechatai.session.mapper.MessageMapper;
import com.wechatai.tool.model.dto.ScreenshotResult;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.AgentToolRegistry;
import com.wechatai.tool.registry.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具执行节点 — 解析 tool_calls，反射调用 @Tool 方法，回填结果。
 * <p>
 * 执行流程：
 * 1. 从状态取 toolExecutionRequests（List<Map> 格式）
 * 2. 逐个查找对应的 @Tool Bean + Method
 * 3. 解析参数 JSON → Java 类型，反射调用
 * 4. 结果追加到 messageHistory，标记 needTool=false
 */
public class ToolExecuteNode implements AsyncNodeAction<ChatGraphState> {

    private static final Logger log = LoggerFactory.getLogger(ToolExecuteNode.class);

    /** Filesystem MCP 会产出可下载文件的工具 */
    private static final Set<String> FILE_PRODUCING_MCP_TOOLS = Set.of(
            "write_file", "edit_file", "move_file");

    private static final ExecutorService AGENT_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-parallel");
        t.setDaemon(true);
        return t;
    });

    private final ToolRegistry toolRegistry;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentGraphRunner agentGraphRunner;
    private final MessageMapper messageMapper;
    private final RunWatchdog watchdog;
    private final UsageMetricsService usageMetricsService;
    private final ParallelAgentBatchService parallelBatchService;
    private final DesktopProgressEmitter progressEmitter;
    private final ObjectMapper objectMapper;
    /** filesystem MCP 沙箱根目录（与 mcp.filesystem.allowed-dir 一致） */
    private final Path sandboxRoot;
    /** generateDocument 产物根目录（与 docgen.storage-path 一致） */
    private final Path docgenRoot;

    public ToolExecuteNode(ToolRegistry toolRegistry, AgentToolRegistry agentToolRegistry,
                           AgentGraphRunner agentGraphRunner, MessageMapper messageMapper,
                           RunWatchdog watchdog, String sandboxDir,
                           UsageMetricsService usageMetricsService,
                           ParallelAgentBatchService parallelBatchService) {
        this(toolRegistry, agentToolRegistry, agentGraphRunner, messageMapper, watchdog,
                sandboxDir, "./AItext", usageMetricsService, parallelBatchService, null);
    }

    public ToolExecuteNode(ToolRegistry toolRegistry, AgentToolRegistry agentToolRegistry,
                           AgentGraphRunner agentGraphRunner, MessageMapper messageMapper,
                           RunWatchdog watchdog, String sandboxDir, String docgenDir,
                           UsageMetricsService usageMetricsService,
                           ParallelAgentBatchService parallelBatchService) {
        this(toolRegistry, agentToolRegistry, agentGraphRunner, messageMapper, watchdog,
                sandboxDir, docgenDir, usageMetricsService, parallelBatchService, null);
    }

    public ToolExecuteNode(ToolRegistry toolRegistry, AgentToolRegistry agentToolRegistry,
                           AgentGraphRunner agentGraphRunner, MessageMapper messageMapper,
                           RunWatchdog watchdog, String sandboxDir, String docgenDir,
                           UsageMetricsService usageMetricsService,
                           ParallelAgentBatchService parallelBatchService,
                           DesktopProgressEmitter progressEmitter) {
        this.toolRegistry = toolRegistry;
        this.agentToolRegistry = agentToolRegistry;
        this.agentGraphRunner = agentGraphRunner;
        this.messageMapper = messageMapper;
        this.watchdog = watchdog;
        this.usageMetricsService = usageMetricsService;
        this.parallelBatchService = parallelBatchService;
        this.progressEmitter = progressEmitter;
        this.objectMapper = new ObjectMapper();
        this.sandboxRoot = Paths.get(sandboxDir != null ? sandboxDir : "./upload")
                .toAbsolutePath().normalize();
        this.docgenRoot = Paths.get(docgenDir != null ? docgenDir : "./AItext")
                .toAbsolutePath().normalize();
    }

    private void progress(String sessionId, String text) {
        if (progressEmitter != null) {
            progressEmitter.emit(sessionId, text);
        }
    }

    private void recordToolMetric(String toolName, long elapsedMs, boolean success) {
        if (usageMetricsService != null) {
            usageMetricsService.recordTool(toolName, elapsedMs, success);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(ChatGraphState state) {
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.warn("【工具执行】无工具调用请求");
            return CompletableFuture.completedFuture(Map.of(
                    ChatGraphState.KEY_NEED_TOOL, false
            ));
        }

        // 获取消息历史
        List<Map<String, Object>> messages = (List<Map<String, Object>>)
                state.data().get("messageHistory");
        if (messages == null) {
            messages = new ArrayList<>();
        }

        String sessionId = state.getSessionId();

        // 收集工具产生的文件附件（截图等），注入到 state 供发送层使用；多轮合并勿丢
        List<Map<String, Object>> attachments = new ArrayList<>(state.getToolAttachments());

        // ≥2 个子 Agent：并行执行；WRITE 确认排队（不丢弃并行结果）
        if (countRunnableAgents(toolCalls, state) >= 2) {
            progress(sessionId, "正在并行处理多个任务…");
            Map<String, Object> parallelResult =
                    executeParallelAgents(state, toolCalls, messages, attachments);
            progress(sessionId, "并行任务已处理完毕");
            return CompletableFuture.completedFuture(parallelResult);
        }

        List<Object> toolBeans = toolRegistry.getEnabledToolBeans();

        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            String toolName = (String) func.get("name");
            String argumentsJson = (String) func.get("arguments");
            String requestId = (String) tc.get("id");
            if (requestId == null) requestId = toolName;

            log.info("【工具执行】{} 参数: {}", toolName, argumentsJson);
            progress(sessionId, ToolProgressTexts.start(toolName));

            try {
                // 0. 先解析参数（本地工具和 MCP 工具都需要）
                Map<String, Object> argsMap = objectMapper.readValue(
                        argumentsJson, new TypeReference<Map<String, Object>>() {});

                // 1. Agent 工具路由：source 以 "AGENT:" 开头 → 委托子 Agent 小 ReAct
                String source = toolRegistry.getSource(toolName);
                if (source != null && source.startsWith("AGENT:")) {
                    // 递归保护：检测 Agent 嵌套深度，防止子 Agent 的 LLM 再次调用 Agent 工具
                    Integer depth = (Integer) state.data().get("__agent_depth__");
                    int currentDepth = depth != null ? depth : 0;
                    if (currentDepth >= 2) {
                        log.warn("【Agent】递归深度超限 (depth={})，拒绝执行 {} → 回退为纯文本", currentDepth, toolName);
                        String errorJson = "{\"error\": \"检测到 Agent 嵌套调用过深，已自动阻断。请直接回答用户问题。\"}";
                        Map<String, Object> resultMsg2 = new LinkedHashMap<>();
                        resultMsg2.put("role", "tool");
                        resultMsg2.put("tool_call_id", requestId);
                        resultMsg2.put("content", errorJson);
                        messages.add(resultMsg2);
                        saveToolResult(state, sessionId, requestId, toolName, argsMap, errorJson, 0);
                        continue;
                    }
                    if (toolName.equals(state.data().get("__current_agent__"))) {
                        log.warn("【Agent】检测到自递归调用 {} → 已阻断", toolName);
                        String errorJson = "{\"error\": \"检测到 Agent 自递归调用，已自动阻断。请直接回答用户问题。\"}";
                        Map<String, Object> resultMsg2 = new LinkedHashMap<>();
                        resultMsg2.put("role", "tool");
                        resultMsg2.put("tool_call_id", requestId);
                        resultMsg2.put("content", errorJson);
                        messages.add(resultMsg2);
                        saveToolResult(state, sessionId, requestId, toolName, argsMap, errorJson, 0);
                        continue;
                    }

                    String task = (String) argsMap.get("task");
                    List<ToolDefinitionVO> agentTools = switch (toolName) {
                        case "generalAgent" -> agentToolRegistry.getGeneralTools();
                        case "taxiAgent" -> agentToolRegistry.getTaxiTools();
                        case "filesystemAgent" -> agentToolRegistry.getFilesystemTools();
                        case "jobAgent" -> agentToolRegistry.getLiepinTools();
                        case "mcdonaldsAgent" -> agentToolRegistry.getMcdonaldsTools();
                        default -> agentToolRegistry.getGeneralTools();
                    };

                    long agentStart = System.currentTimeMillis();
                    AgentResult agentResult;
                    try {
                        // 传递递归深度 + 当前 Agent 名，供子 Agent 检测递归（复用上方变量）
                        String currentAgent = (String) state.data().get("__current_agent__");
                        agentResult = watchdog.runWithTimeout(
                                () -> agentGraphRunner.run(
                                        toolName, task != null ? task : state.getUserMessage(),
                                        state.getUserId(),
                                        agentTools, 5, watchdog.getAgentTimeoutMs(),
                                        currentDepth, currentAgent,
                                        state.getSessionId(), state.getChannel()),
                                watchdog.getAgentTimeoutMs(), toolName);
                    } catch (TimeoutException e) {
                        agentResult = AgentResult.fail(
                                "工具执行超时(" + watchdog.getAgentTimeoutMs() / 1000 + "s)", 0, 0);
                    }
                    long agentElapsed = System.currentTimeMillis() - agentStart;

                    String resultJson;
                    if (agentResult.needsConfirm()) {
                        // 子 Agent WRITE 工具需要确认 → 构造确认请求，走中央中断流程
                        resultJson = objectMapper.writeValueAsString(Map.of(
                                "needsConfirm", true,
                                "threadId", agentResult.getConfirmThreadId(),
                                "toolName", agentResult.getConfirmToolName(),
                                "confirmMsg", agentResult.getSummary()
                        ));
                        // 临时标记为 WRITE，触发中央图中断
                        argsMap.put("__sub_agent_confirm__", agentResult.getConfirmThreadId());
                        log.info("【Agent】{} WRITE 工具 {} 等待用户确认, threadId={}",
                                toolName, agentResult.getConfirmToolName(), agentResult.getConfirmThreadId());
                    } else if (agentResult.isOk()) {
                        resultJson = agentResult.getSummary();
                        // 收集子 Agent 产生的附件（截图等）
                        if (agentResult.getAttachments() != null) {
                            attachments.addAll(agentResult.getAttachments());
                        }
                        log.info("【Agent】{} 完成 ({}ms, {}轮): {}",
                                toolName, agentElapsed, agentResult.getRounds(),
                                truncate(resultJson, 150));
                    } else {
                        resultJson = "{\"error\": \"" + agentResult.getError() + "\"}";
                        log.warn("【Agent】{} 失败: {}", toolName, agentResult.getError());
                    }

                    Map<String, Object> resultMsg = new LinkedHashMap<>();
                    resultMsg.put("role", "tool");
                    resultMsg.put("tool_call_id", requestId);
                    resultMsg.put("content", resultJson);
                    messages.add(resultMsg);

                    saveToolResult(state, sessionId, requestId, toolName, argsMap, resultJson, agentElapsed);
                    toolRegistry.recordHistory(requestId, toolName, argumentsJson,
                            agentResult.isOk(), truncate(resultJson, 100), System.currentTimeMillis());
                    recordToolMetric(toolName, agentElapsed, agentResult.isOk());
                    progress(sessionId, ToolProgressTexts.finish(toolName, resultJson));

                    continue;
                }

                // 2. 外部工具路由（MCP）：跳过本地 Bean 扫描，直接走 MCP 客户端执行
                if (toolRegistry.isExternalTool(toolName)) {
                    // MCP 工具不需要 sessionId/userId，去掉避免干扰
                    Map<String, Object> cleanArgs = new LinkedHashMap<>(argsMap);
                    cleanArgs.remove("sessionId");
                    cleanArgs.remove("userId");
                    String mcpArgsJson = objectMapper.writeValueAsString(cleanArgs);

                    long toolStart = System.currentTimeMillis();
                    String resultJson;
                    try {
                        resultJson = watchdog.runWithTimeout(
                                () -> toolRegistry.executeExternalTool(toolName, mcpArgsJson),
                                watchdog.getToolTimeoutMs(), toolName);
                    } catch (TimeoutException e) {
                        resultJson = "{\"error\":\"工具执行超时("
                                + watchdog.getToolTimeoutMs() / 1000 + "s)\"}";
                    }
                    long toolElapsed = System.currentTimeMillis() - toolStart;

                    // 构造 tool 结果消息
                    Map<String, Object> resultMsg = new LinkedHashMap<>();
                    resultMsg.put("role", "tool");
                    resultMsg.put("tool_call_id", requestId);
                    resultMsg.put("content", resultJson);
                    messages.add(resultMsg);

                    // 持久化工具调用结果到 MySQL（用原始 argsMap 含 sessionId/userId）
                    saveToolResult(state, sessionId, requestId, toolName, argsMap, resultJson, toolElapsed);

                    log.info("【MCP 工具】{} 完成, 结果: {}", toolName, truncate(resultJson, 200));

                    collectMcpFileAttachment(attachments, toolName, argsMap, resultJson);
                    collectRouteAttachment(attachments, toolName, argsMap, resultJson);

                    // 记录工具调用历史
                    toolRegistry.recordHistory(requestId, toolName, argumentsJson,
                            true, truncate(resultJson, 100), System.currentTimeMillis());
                    recordToolMetric(toolName, toolElapsed, resultJson == null || !resultJson.contains("\"error\""));
                    progress(sessionId, ToolProgressTexts.finish(toolName, resultJson));

                    continue; // 跳过本地反射执行
                }

                // 3. 本地 @Tool 路由：通过 Bean 反射执行
                Object bean = findBeanByToolName(toolBeans, toolName);
                if (bean == null) {
                    throw new IllegalArgumentException("未找到工具: " + toolName);
                }

                // 注入 sessionId、userId、channel 到所有本地工具调用
                argsMap.put("sessionId", sessionId);
                argsMap.put("userId", state.getUserId());
                if (state.getChannel() != null) {
                    argsMap.put("channel", state.getChannel());
                }

                long toolStart = System.currentTimeMillis();
                String resultJson;
                try {
                    resultJson = watchdog.runWithTimeout(() -> {
                        Object raw = invokeRaw(bean, toolName, argsMap);
                        collectAttachments(attachments, raw);
                        return serializeResult(raw);
                    }, watchdog.getToolTimeoutMs(), toolName);
                } catch (TimeoutException e) {
                    resultJson = "{\"error\":\"工具执行超时("
                            + watchdog.getToolTimeoutMs() / 1000 + "s)\"}";
                }
                long toolElapsed = System.currentTimeMillis() - toolStart;

                // 构造 tool 结果消息
                Map<String, Object> resultMsg = new LinkedHashMap<>();
                resultMsg.put("role", "tool");
                resultMsg.put("tool_call_id", requestId);
                resultMsg.put("content", resultJson);
                messages.add(resultMsg);

                // 持久化工具调用结果到 MySQL（子 Agent 内部不写父会话，见 saveToolResult）
                saveToolResult(state, sessionId, requestId, toolName, argsMap, resultJson, toolElapsed);

                log.info("【工具执行】{} 完成, 结果: {}", toolName, truncate(resultJson, 200));

                // 记录工具调用历史
                toolRegistry.recordHistory(requestId, toolName, argumentsJson,
                        true, truncate(resultJson, 100), System.currentTimeMillis());
                recordToolMetric(toolName, toolElapsed, resultJson == null || !resultJson.contains("\"error\""));
                progress(sessionId, ToolProgressTexts.finish(toolName, resultJson));

            } catch (Exception e) {
                log.error("【工具执行】{} 失败", toolName, e);
                toolRegistry.recordHistory(requestId, toolName, argumentsJson,
                        false, e.getMessage(), System.currentTimeMillis());
                String errorJson = "{\"error\": \"" + e.getMessage() + "\"}";
                Map<String, Object> errorMsg = new LinkedHashMap<>();
                errorMsg.put("role", "tool");
                errorMsg.put("tool_call_id", requestId);
                errorMsg.put("content", errorJson);
                messages.add(errorMsg);

                // 同步写 TOOL_RESULT 到 message 表，防止 DB 链断裂
                // 参数解析失败时 argsMap 不可用，传空 Map
                Map<String, Object> errArgs;
                try {
                    errArgs = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception ex) {
                    errArgs = new LinkedHashMap<>();
                }
                saveToolResult(state, sessionId, requestId, toolName, errArgs, errorJson, System.currentTimeMillis());
                recordToolMetric(toolName, 0, false);
                progress(sessionId, ToolProgressTexts.finish(toolName, errorJson));
            }
        }

        // 返回完整 messageHistory + 附件列表（LangGraph 每次节点返回后新建状态，临时对象会丢失）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageHistory", messages);
        result.put(ChatGraphState.KEY_NEED_TOOL, false);
        result.put(ChatGraphState.KEY_TOOL_ATTACHMENTS, attachments);
        return CompletableFuture.completedFuture(result);
    }

    @SuppressWarnings("unchecked")
    private int countRunnableAgents(List<Map<String, Object>> toolCalls, ChatGraphState state) {
        int n = 0;
        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            if (func == null) continue;
            if (isRunnableAgent((String) func.get("name"), state)) n++;
        }
        return n;
    }

    private boolean isRunnableAgent(String toolName, ChatGraphState state) {
        if (toolName == null) return false;
        String source = toolRegistry.getSource(toolName);
        if (source == null || !source.startsWith("AGENT:")) return false;
        Integer depth = (Integer) state.data().get("__agent_depth__");
        int currentDepth = depth != null ? depth : 0;
        if (currentDepth >= 2) return false;
        return !toolName.equals(state.data().get("__current_agent__"));
    }

    /**
     * 多子 Agent 并行：等全部跑完或停在 WRITE；有确认则注册队列（按停下先后），
     * 已完成结果保留，图直接结束等待用户逐个确认后汇总。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeParallelAgents(ChatGraphState state,
                                                      List<Map<String, Object>> toolCalls,
                                                      List<Map<String, Object>> messages,
                                                      List<Map<String, Object>> attachments) {
        String sessionId = state.getSessionId();
        List<AgentWork> agentWorks = new ArrayList<>();
        List<Integer> nonAgentIdx = new ArrayList<>();

        for (int i = 0; i < toolCalls.size(); i++) {
            Map<String, Object> tc = toolCalls.get(i);
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            String name = func != null ? (String) func.get("name") : null;
            if (isRunnableAgent(name, state)) {
                agentWorks.add(new AgentWork(i, tc, name));
            } else {
                nonAgentIdx.add(i);
            }
        }

        log.info("【Agent并行】启动 {} 个子 Agent + {} 个其它工具", agentWorks.size(), nonAgentIdx.size());
        Object metricsCtx = usageMetricsService != null ? usageMetricsService.captureTurnContext() : null;
        long t0 = System.currentTimeMillis();

        List<CompletableFuture<AgentOutcome>> futures = new ArrayList<>();
        for (AgentWork w : agentWorks) {
            futures.add(CompletableFuture.supplyAsync(() -> runAgentParallel(state, w, metricsCtx), AGENT_POOL));
        }

        AgentOutcome[] agentOutcomes = new AgentOutcome[agentWorks.size()];
        for (int i = 0; i < futures.size(); i++) {
            try {
                agentOutcomes[i] = futures.get(i).join();
            } catch (Exception e) {
                log.error("【Agent并行】{} 异常", agentWorks.get(i).toolName, e);
                agentOutcomes[i] = AgentOutcome.error(agentWorks.get(i), e.getMessage());
            }
        }

        // 非 Agent 串行（保持副作用顺序）
        CallOutcome[] all = new CallOutcome[toolCalls.size()];
        for (int i = 0; i < agentWorks.size(); i++) {
            all[agentWorks.get(i).index] = agentOutcomes[i].toCallOutcome();
        }
        List<Object> toolBeans = toolRegistry.getEnabledToolBeans();
        for (int idx : nonAgentIdx) {
            all[idx] = executeNonAgentCall(state, toolCalls.get(idx), toolBeans, attachments);
        }

        // 组装批次：有 WRITE 则排队，无 WRITE 则只落库给中央继续
        // 批次 key：优先 sessionId，微信等无 session 时用 userId
        String batchKey = sessionId != null && !sessionId.isBlank()
                ? sessionId : state.getUserId();
        Batch batch = null;
        if (batchKey != null && parallelBatchService != null) {
            boolean anyConfirm = false;
            for (AgentOutcome o : agentOutcomes) {
                if (o.needsConfirm) {
                    anyConfirm = true;
                    break;
                }
            }
            if (anyConfirm) {
                batch = new Batch(batchKey);
                // 按原始 tool_calls 顺序建槽；确认按 stoppedAt 入队后 register 再排序
                for (int i = 0; i < agentWorks.size(); i++) {
                    AgentOutcome o = agentOutcomes[i];
                    AgentWork w = agentWorks.get(i);
                    if (o.needsConfirm) {
                        batch.addSlot(new SlotResult(o.requestId, w.toolName, null, false, List.of()));
                        batch.enqueueConfirm(new ConfirmItem(
                                o.requestId, w.toolName, o.threadId, o.confirmToolName,
                                o.confirmMsg, o.stoppedAtMs));
                    } else {
                        batch.addSlot(new SlotResult(o.requestId, w.toolName, o.resultJson, o.success,
                                o.extraAttachments));
                    }
                }
                for (CallOutcome o : all) {
                    if (o != null && o.extraAttachments != null) {
                        batch.extraAttachments.addAll(o.extraAttachments);
                    }
                }
                parallelBatchService.register(batch);
            }
        }

        for (CallOutcome o : all) {
            if (o == null) continue;
            messages.add(o.resultMsg);
            if (o.extraAttachments != null && !o.extraAttachments.isEmpty()) {
                attachments.addAll(o.extraAttachments);
            }
            saveToolResult(state, sessionId, o.requestId, o.toolName, o.argsMap, o.resultJson, o.elapsedMs);
            toolRegistry.recordHistory(o.requestId, o.toolName, o.argumentsJson,
                    o.success, truncate(o.resultJson, 100), System.currentTimeMillis());
            recordToolMetric(o.toolName, o.elapsedMs, o.success);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageHistory", messages);
        result.put(ChatGraphState.KEY_NEED_TOOL, false);
        result.put(ChatGraphState.KEY_TOOL_ATTACHMENTS, attachments);

        if (batch != null && !batch.confirmQueue.isEmpty()) {
            ConfirmItem first = batch.confirmQueue.get(0);
            // register 已按 stoppedAt 排序
            String confirmReply = ParallelAgentBatchService.formatConfirmMessage(
                    first, batch.confirmQueue.size());
            result.put(ChatGraphState.KEY_AWAIT_PARALLEL_CONFIRM, true);
            result.put(ChatGraphState.KEY_REPLY, confirmReply);
            result.put(ChatGraphState.KEY_FINISH_REASON, "NEED_CONFIRM");
            log.info("【Agent并行】收齐 ({}ms)，{} 项 WRITE 待确认，队头={} threadId={}",
                    System.currentTimeMillis() - t0, batch.confirmQueue.size(),
                    first.agentName, first.threadId);
        } else {
            log.info("【Agent并行】全部完成无 WRITE ({}ms)，交回中央 LLM",
                    System.currentTimeMillis() - t0);
        }
        return result;
    }

    private AgentOutcome runAgentParallel(ChatGraphState state, AgentWork w, Object metricsCtx) {
        if (usageMetricsService != null && metricsCtx != null) {
            final AgentOutcome[] box = new AgentOutcome[1];
            usageMetricsService.runWithTurnContext(metricsCtx, () -> box[0] = executeAgentOutcome(state, w));
            return box[0];
        }
        return executeAgentOutcome(state, w);
    }

    @SuppressWarnings("unchecked")
    private AgentOutcome executeAgentOutcome(ChatGraphState state, AgentWork w) {
        Map<String, Object> func = (Map<String, Object>) w.tc.get("function");
        String argumentsJson = (String) func.get("arguments");
        String requestId = (String) w.tc.get("id");
        if (requestId == null) requestId = w.toolName;
        long stoppedAt = System.currentTimeMillis();
        try {
            Map<String, Object> argsMap = objectMapper.readValue(
                    argumentsJson, new TypeReference<Map<String, Object>>() {});
            String task = (String) argsMap.get("task");
            List<ToolDefinitionVO> agentTools = resolveAgentTools(w.toolName);
            Integer depth = (Integer) state.data().get("__agent_depth__");
            int currentDepth = depth != null ? depth : 0;
            String currentAgent = (String) state.data().get("__current_agent__");

            log.info("【Agent并行】启动 {} requestId={}", w.toolName, requestId);
            long agentStart = System.currentTimeMillis();
            AgentResult agentResult;
            try {
                agentResult = watchdog.runWithTimeout(
                        () -> agentGraphRunner.run(
                                w.toolName, task != null ? task : state.getUserMessage(),
                                state.getUserId(), agentTools, 5, watchdog.getAgentTimeoutMs(),
                                currentDepth, currentAgent,
                                state.getSessionId(), state.getChannel()),
                        watchdog.getAgentTimeoutMs(), w.toolName);
            } catch (TimeoutException e) {
                agentResult = AgentResult.fail(
                        "工具执行超时(" + watchdog.getAgentTimeoutMs() / 1000 + "s)", 0, 0);
            }
            long elapsed = System.currentTimeMillis() - agentStart;
            stoppedAt = System.currentTimeMillis();

            if (agentResult.needsConfirm()) {
                argsMap.put("__sub_agent_confirm__", agentResult.getConfirmThreadId());
                String resultJson = objectMapper.writeValueAsString(Map.of(
                        "needsConfirm", true,
                        "threadId", agentResult.getConfirmThreadId(),
                        "toolName", agentResult.getConfirmToolName(),
                        "confirmMsg", agentResult.getSummary()
                ));
                log.info("【Agent并行】{} 停在 WRITE ({}ms) threadId={}",
                        w.toolName, elapsed, agentResult.getConfirmThreadId());
                return AgentOutcome.confirm(w, requestId, argumentsJson, argsMap, resultJson,
                        elapsed, stoppedAt, agentResult);
            }
            if (agentResult.isOk()) {
                List<Map<String, Object>> att = agentResult.getAttachments() != null
                        ? new ArrayList<>(agentResult.getAttachments()) : List.of();
                log.info("【Agent并行】{} 完成 ({}ms)", w.toolName, elapsed);
                return AgentOutcome.done(w, requestId, argumentsJson, argsMap,
                        agentResult.getSummary(), elapsed, true, att, stoppedAt);
            }
            String err = "{\"error\": \"" + agentResult.getError() + "\"}";
            return AgentOutcome.done(w, requestId, argumentsJson, argsMap,
                    err, elapsed, false, List.of(), stoppedAt);
        } catch (Exception e) {
            log.error("【Agent并行】{} 失败", w.toolName, e);
            return AgentOutcome.error(w, e.getMessage());
        }
    }

    private List<ToolDefinitionVO> resolveAgentTools(String toolName) {
        return switch (toolName) {
            case "generalAgent" -> agentToolRegistry.getGeneralTools();
            case "taxiAgent" -> agentToolRegistry.getTaxiTools();
            case "filesystemAgent" -> agentToolRegistry.getFilesystemTools();
            case "jobAgent" -> agentToolRegistry.getLiepinTools();
            case "mcdonaldsAgent" -> agentToolRegistry.getMcdonaldsTools();
            default -> agentToolRegistry.getGeneralTools();
        };
    }

    /** 并行批次中的非 Agent 工具（MCP / 本地），逻辑与串行路径一致 */
    @SuppressWarnings("unchecked")
    private CallOutcome executeNonAgentCall(ChatGraphState state, Map<String, Object> tc,
                                            List<Object> toolBeans,
                                            List<Map<String, Object>> attachmentsSink) {
        Map<String, Object> func = (Map<String, Object>) tc.get("function");
        String toolName = (String) func.get("name");
        String argumentsJson = (String) func.get("arguments");
        String requestId = (String) tc.get("id");
        if (requestId == null) requestId = toolName;
        String sessionId = state.getSessionId();
        try {
            Map<String, Object> argsMap = objectMapper.readValue(
                    argumentsJson, new TypeReference<Map<String, Object>>() {});
            String source = toolRegistry.getSource(toolName);
            if (source != null && source.startsWith("AGENT:")) {
                // 被递归阻断的 Agent：走错误
                return CallOutcome.error(toolName, requestId, argumentsJson, argsMap,
                        "Agent 不可并行执行（递归保护）", 0);
            }
            if (toolRegistry.isExternalTool(toolName)) {
                Map<String, Object> cleanArgs = new LinkedHashMap<>(argsMap);
                cleanArgs.remove("sessionId");
                cleanArgs.remove("userId");
                String mcpArgsJson = objectMapper.writeValueAsString(cleanArgs);
                long start = System.currentTimeMillis();
                String resultJson;
                try {
                    resultJson = watchdog.runWithTimeout(
                            () -> toolRegistry.executeExternalTool(toolName, mcpArgsJson),
                            watchdog.getToolTimeoutMs(), toolName);
                } catch (TimeoutException e) {
                    resultJson = "{\"error\":\"工具执行超时("
                            + watchdog.getToolTimeoutMs() / 1000 + "s)\"}";
                }
                long elapsed = System.currentTimeMillis() - start;
                List<Map<String, Object>> mcpAtt = new ArrayList<>();
                collectMcpFileAttachment(mcpAtt, toolName, argsMap, resultJson);
                collectRouteAttachment(mcpAtt, toolName, argsMap, resultJson);
                return CallOutcome.of(toolName, requestId, argumentsJson, argsMap, resultJson,
                        elapsed, resultJson == null || !resultJson.contains("\"error\""), mcpAtt);
            }
            Object bean = findBeanByToolName(toolBeans, toolName);
            if (bean == null) throw new IllegalArgumentException("未找到工具: " + toolName);
            argsMap.put("sessionId", sessionId);
            argsMap.put("userId", state.getUserId());
            if (state.getChannel() != null) argsMap.put("channel", state.getChannel());
            List<Map<String, Object>> localAtt = new ArrayList<>();
            long start = System.currentTimeMillis();
            String resultJson;
            try {
                resultJson = watchdog.runWithTimeout(() -> {
                    Object raw = invokeRaw(bean, toolName, argsMap);
                    collectAttachments(localAtt, raw);
                    return serializeResult(raw);
                }, watchdog.getToolTimeoutMs(), toolName);
            } catch (TimeoutException e) {
                resultJson = "{\"error\":\"工具执行超时("
                        + watchdog.getToolTimeoutMs() / 1000 + "s)\"}";
            }
            return CallOutcome.of(toolName, requestId, argumentsJson, argsMap, resultJson,
                    System.currentTimeMillis() - start,
                    resultJson == null || !resultJson.contains("\"error\""), localAtt);
        } catch (Exception e) {
            Map<String, Object> errArgs = new LinkedHashMap<>();
            return CallOutcome.error(toolName, requestId, argumentsJson, errArgs, e.getMessage(), 0);
        }
    }

    private record AgentWork(int index, Map<String, Object> tc, String toolName) {}

    private static final class AgentOutcome {
        final AgentWork work;
        final String requestId;
        final String argumentsJson;
        final Map<String, Object> argsMap;
        final String resultJson;
        final long elapsedMs;
        final long stoppedAtMs;
        final boolean success;
        final boolean needsConfirm;
        final String threadId;
        final String confirmToolName;
        final String confirmMsg;
        final List<Map<String, Object>> extraAttachments;

        private AgentOutcome(AgentWork work, String requestId, String argumentsJson,
                             Map<String, Object> argsMap, String resultJson, long elapsedMs,
                             long stoppedAtMs, boolean success, boolean needsConfirm,
                             String threadId, String confirmToolName, String confirmMsg,
                             List<Map<String, Object>> extraAttachments) {
            this.work = work;
            this.requestId = requestId;
            this.argumentsJson = argumentsJson;
            this.argsMap = argsMap != null ? argsMap : new LinkedHashMap<>();
            this.resultJson = resultJson;
            this.elapsedMs = elapsedMs;
            this.stoppedAtMs = stoppedAtMs;
            this.success = success;
            this.needsConfirm = needsConfirm;
            this.threadId = threadId;
            this.confirmToolName = confirmToolName;
            this.confirmMsg = confirmMsg;
            this.extraAttachments = extraAttachments != null ? extraAttachments : List.of();
        }

        static AgentOutcome confirm(AgentWork w, String requestId, String argumentsJson,
                                    Map<String, Object> argsMap, String resultJson,
                                    long elapsed, long stoppedAt, AgentResult ar) {
            return new AgentOutcome(w, requestId, argumentsJson, argsMap, resultJson, elapsed, stoppedAt,
                    false, true, ar.getConfirmThreadId(), ar.getConfirmToolName(), ar.getSummary(), List.of());
        }

        static AgentOutcome done(AgentWork w, String requestId, String argumentsJson,
                                 Map<String, Object> argsMap, String resultJson, long elapsed,
                                 boolean ok, List<Map<String, Object>> att, long stoppedAt) {
            return new AgentOutcome(w, requestId, argumentsJson, argsMap, resultJson, elapsed, stoppedAt,
                    ok, false, null, null, null, att);
        }

        static AgentOutcome error(AgentWork w, String message) {
            String requestId = w.tc.get("id") != null ? String.valueOf(w.tc.get("id")) : w.toolName;
            String argumentsJson = "";
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> func = (Map<String, Object>) w.tc.get("function");
                if (func != null && func.get("arguments") != null) {
                    argumentsJson = String.valueOf(func.get("arguments"));
                }
            } catch (Exception ignored) {
            }
            String resultJson = "{\"error\": \"" + (message != null ? message.replace("\"", "'") : "?") + "\"}";
            return new AgentOutcome(w, requestId, argumentsJson, new LinkedHashMap<>(), resultJson,
                    0, System.currentTimeMillis(), false, false, null, null, null, List.of());
        }

        CallOutcome toCallOutcome() {
            return CallOutcome.of(work.toolName, requestId, argumentsJson, argsMap, resultJson,
                    elapsedMs, success, extraAttachments);
        }
    }

    private static final class CallOutcome {
        final String toolName;
        final String requestId;
        final String argumentsJson;
        final Map<String, Object> argsMap;
        final String resultJson;
        final long elapsedMs;
        final boolean success;
        final List<Map<String, Object>> extraAttachments;
        final Map<String, Object> resultMsg;

        private CallOutcome(String toolName, String requestId, String argumentsJson,
                            Map<String, Object> argsMap, String resultJson, long elapsedMs,
                            boolean success, List<Map<String, Object>> extraAttachments) {
            this.toolName = toolName;
            this.requestId = requestId;
            this.argumentsJson = argumentsJson != null ? argumentsJson : "";
            this.argsMap = argsMap != null ? argsMap : new LinkedHashMap<>();
            this.resultJson = resultJson != null ? resultJson : "";
            this.elapsedMs = elapsedMs;
            this.success = success;
            this.extraAttachments = extraAttachments != null ? extraAttachments : List.of();
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "tool");
            msg.put("tool_call_id", requestId);
            msg.put("content", this.resultJson);
            this.resultMsg = msg;
        }

        static CallOutcome of(String toolName, String requestId, String argumentsJson,
                              Map<String, Object> argsMap, String resultJson, long elapsedMs,
                              boolean success, List<Map<String, Object>> extraAtt) {
            return new CallOutcome(toolName, requestId, argumentsJson, argsMap,
                    resultJson, elapsedMs, success, extraAtt);
        }

        static CallOutcome error(String toolName, String requestId, String argumentsJson,
                                 Map<String, Object> argsMap, String message, long elapsedMs) {
            String resultJson = "{\"error\": \"" + (message != null ? message.replace("\"", "'") : "?") + "\"}";
            return new CallOutcome(toolName, requestId, argumentsJson, argsMap,
                    resultJson, elapsedMs, false, List.of());
        }
    }

    private Object findBeanByToolName(List<Object> toolBeans, String toolName) {
        for (Object bean : toolBeans) {
            for (Method method : bean.getClass().getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null && method.getName().equals(toolName)) {
                    return bean;
                }
            }
        }
        return null;
    }

    private String invokeToolMethod(Object bean, String toolName, Map<String, Object> argsMap) throws Exception {
        Object rawResult = invokeRaw(bean, toolName, argsMap);
        return serializeResult(rawResult);
    }

    private Object invokeRaw(Object bean, String toolName, Map<String, Object> argsMap) throws Exception {
        Method targetMethod = findToolMethod(bean, toolName);
        if (targetMethod == null) {
            throw new IllegalArgumentException("方法不存在: " + toolName);
        }

        Parameter[] params = targetMethod.getParameters();
        Object[] paramValues = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String paramName = params[i].getName();
            Object rawValue = argsMap.get(paramName);
            paramValues[i] = convertType(rawValue, params[i].getType());
        }

        return targetMethod.invoke(bean, paramValues);
    }

    private String serializeResult(Object result) throws Exception {
        if (result == null) return "null";
        if (result instanceof String) return (String) result;
        return objectMapper.writeValueAsString(result);
    }

    private Method findToolMethod(Object bean, String toolName) {
        for (Method method : bean.getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && method.getName().equals(toolName)) {
                return method;
            }
        }
        return null;
    }

    private Object convertType(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType == String.class) return value.toString();
        if (targetType == int.class || targetType == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }
        if (targetType == double.class || targetType == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) return value;
            return Boolean.parseBoolean(value.toString());
        }
        try {
            return objectMapper.convertValue(value, objectMapper.getTypeFactory().constructType(targetType));
        } catch (Exception e) {
            log.warn("类型转换失败: {} → {}", value.getClass(), targetType, e);
            return value;
        }
    }

    private static final Pattern FILE_MARKER = Pattern.compile("\\[FILE:([^\\]]+)\\]");

    /**
     * 检测工具返回值是否包含文件附件（截图 / 生成文档），收集到 attachments。
     * <p>
     * 额外写入 {@code fileName}/{@code url}，供桌面端预览与下载（微信仍用 {@code filePath}）。
     */
    private void collectAttachments(List<Map<String, Object>> attachments, Object rawResult) {
        if (rawResult instanceof ScreenshotResult sr && sr.isSuccess()) {
            String path = sr.getFilePath();
            String fileName = path != null ? Paths.get(path).getFileName().toString() : null;
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("type", "image");
            att.put("filePath", path);
            if (fileName != null) {
                att.put("fileName", fileName);
                att.put("url", ApiPrefix.CHAT + "/screenshots/" + fileName);
            }
            attachments.add(att);
            log.info("【附件收集】截图文件: {}", path);
            return;
        }
        if (rawResult instanceof String text && text.contains("[FILE:")) {
            Matcher m = FILE_MARKER.matcher(text);
            while (m.find()) {
                String path = m.group(1).trim().replace("\\", "/");
                if (path.isEmpty()) continue;
                addFileAttachment(attachments, path);
            }
        }
    }

    /**
     * 出行/路线类工具结果 → type=route 附件，供桌面平面地图硬编码渲染。
     */
    private void collectRouteAttachment(List<Map<String, Object>> attachments,
                                        String toolName,
                                        Map<String, Object> args,
                                        String resultJson) {
        try {
            Map<String, Object> route = RoutePayloadExtractor.extract(toolName, args, resultJson);
            if (route == null) return;
            // 同轮只保留最新一条路线
            attachments.removeIf(a -> a != null && "route".equals(a.get("type")));
            attachments.add(route);
            log.info("【附件收集】路线 origin={} dest={} pathPts={}",
                    route.get("origin"), route.get("destination"),
                    route.get("path") instanceof List<?> p ? p.size() : 0);
        } catch (Exception e) {
            log.debug("【附件收集】路线提取跳过: {}", e.getMessage());
        }
    }

    /**
     * Filesystem MCP 写文件成功后，把产物挂到附件列表，供桌面端点击打开。
     */
    private void collectMcpFileAttachment(List<Map<String, Object>> attachments,
                                          String toolName,
                                          Map<String, Object> args,
                                          String resultJson) {
        if (toolName == null || !FILE_PRODUCING_MCP_TOOLS.contains(toolName)) return;
        if (resultJson != null && resultJson.contains("\"error\"")) return;
        if (args == null) return;

        String pathArg = null;
        for (String key : List.of("path", "destination", "to", "target")) {
            Object v = args.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                pathArg = String.valueOf(v).trim();
                break;
            }
        }
        if (pathArg == null) return;
        addFileAttachment(attachments, pathArg);
    }

    private void addFileAttachment(List<Map<String, Object>> attachments, String pathArg) {
        // 1) generateDocument 产物（./AItext/日期/文件）— 必须先于沙箱判断
        //    相对路径 AItext/... 若先 resolve 到 upload/ 会生成错误的 /sandbox/AItext/... → 404
        Map<String, Object> generated = generatedDocAttachment(pathArg);
        if (generated != null) {
            attachments.add(generated);
            log.info("【附件收集】生成文档: {} → {}", generated.get("fileName"), generated.get("url"));
            return;
        }

        Path resolved = resolveSandboxFile(pathArg);
        if (resolved != null) {
            String fileName = resolved.getFileName().toString();
            String url = sandboxUrl(resolved);
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("type", "file");
            att.put("filePath", resolved.toString().replace("\\", "/"));
            att.put("fileName", fileName);
            if (url != null) {
                att.put("url", url);
            }
            attachments.add(att);
            log.info("【附件收集】沙箱文件: {} → {}", fileName, url);
            return;
        }

        // 3) 兜底：按路径中的日期目录拼 generated URL
        Path p = Paths.get(pathArg.replace("\\", "/"));
        String fileName = p.getFileName() != null ? p.getFileName().toString() : null;
        if (fileName == null || fileName.isBlank()) return;
        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "file");
        att.put("filePath", toAbsolutePath(pathArg).toString().replace("\\", "/"));
        att.put("fileName", fileName);
        if (p.getNameCount() >= 2) {
            String date = p.getName(p.getNameCount() - 2).toString();
            if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                att.put("url", generatedUrl(date, fileName));
            }
        }
        attachments.add(att);
        log.info("【附件收集】生成文档(兜底): {}", att.get("filePath"));
    }

    /** 识别 docgen 目录下的文件并生成 /chat/generated/{date}/{file} URL */
    private Map<String, Object> generatedDocAttachment(String pathArg) {
        if (pathArg == null || pathArg.isBlank()) return null;
        String norm = pathArg.replace("\\", "/");
        Path abs = toAbsolutePath(norm);

        // 相对路径以 AItext/ 开头时，按 docgenRoot 拼接
        if (!Paths.get(norm).isAbsolute()) {
            String stripped = norm;
            if (stripped.startsWith("./")) stripped = stripped.substring(2);
            if (stripped.startsWith("AItext/")) {
                abs = docgenRoot.resolve(stripped.substring("AItext/".length())).normalize();
            }
        }

        if (!abs.startsWith(docgenRoot)) {
            return null;
        }
        Path rel = docgenRoot.relativize(abs);
        if (rel.getNameCount() < 2) return null;
        String date = rel.getName(0).toString();
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        String fileName = rel.getFileName().toString();
        if (fileName.isBlank()) return null;

        Map<String, Object> att = new LinkedHashMap<>();
        att.put("type", "file");
        att.put("filePath", abs.toString().replace("\\", "/"));
        att.put("fileName", fileName);
        att.put("url", generatedUrl(date, fileName));
        return att;
    }

    private static String generatedUrl(String date, String fileName) {
        return ApiPrefix.CHAT + "/generated/" + date + "/"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Path toAbsolutePath(String pathArg) {
        Path p = Paths.get(pathArg.replace("\\", "/"));
        if (!p.isAbsolute()) {
            p = Paths.get("").toAbsolutePath().resolve(p);
        }
        return p.normalize();
    }

    /** 将相对/绝对路径解析到沙箱内文件；docgen 路径返回 null */
    private Path resolveSandboxFile(String pathArg) {
        if (pathArg == null || pathArg.isBlank()) return null;
        String norm = pathArg.replace("\\", "/");
        // 明显是 AI 生成文档目录，不走沙箱
        String stripped = norm.startsWith("./") ? norm.substring(2) : norm;
        if (stripped.startsWith("AItext/") || stripped.contains("/AItext/")) {
            return null;
        }
        Path candidate = Paths.get(norm);
        if (!candidate.isAbsolute()) {
            candidate = sandboxRoot.resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(sandboxRoot)) return null;
        if (candidate.startsWith(docgenRoot)) return null;
        if (!Files.isRegularFile(candidate)) {
            if (candidate.getFileName() == null) return null;
        }
        return candidate;
    }

    private String sandboxUrl(Path absoluteFile) {
        if (absoluteFile == null || !absoluteFile.startsWith(sandboxRoot)) return null;
        Path rel = sandboxRoot.relativize(absoluteFile);
        StringBuilder sb = new StringBuilder(ApiPrefix.CHAT).append("/sandbox/");
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(rel.getName(i).toString(), StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 保存工具调用结果到 message 表。
     * 子 Agent 图内部的中间工具结果不落库，否则会与外层 taxiAgent 等调用缠在同一会话，下一轮 LLM 400。
     */
    private void saveToolResult(ChatGraphState state, String sessionId, String requestId, String toolName,
                                 Map<String, Object> args, String resultJson, long elapsed) {
        if (sessionId == null) return;
        if (state != null && state.data().get(ChatGraphState.KEY_AGENT_TOOLS) != null) {
            return;
        }
        try {
            MessageEntity entity = new MessageEntity();
            entity.setMessageId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            entity.setRole(MessageRole.TOOL);
            entity.setMessageType(MessageType.TOOL_RESULT);
            entity.setContent(resultJson);
            entity.setToolCalls(objectMapper.writeValueAsString(Map.of(
                    "tool_call_id", requestId,
                    "name", toolName,
                    "arguments", args
            )));
            entity.setLatencyMs((int) elapsed);
            messageMapper.insert(entity);
        } catch (Exception e) {
            log.warn("保存 tool 结果失败: {}", e.getMessage());
        }
    }
}
