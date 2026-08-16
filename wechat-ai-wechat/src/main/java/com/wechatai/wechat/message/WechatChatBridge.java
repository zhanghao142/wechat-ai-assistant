package com.wechatai.wechat.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.agent.AgentResult;
import com.wechatai.ai.agent.AgentGraphRunner;
import com.wechatai.ai.agent.ParallelAgentBatchService;
import com.wechatai.ai.agent.ParallelAgentBatchService.ConfirmItem;
import com.wechatai.ai.agent.ParallelAgentBatchService.ResumeOutcome;
import com.wechatai.ai.ai.RunWatchdog;
import com.wechatai.ai.graph.GraphInterruptHandler;
import com.wechatai.ai.graph.GraphStateStore;
import com.wechatai.ai.graph.GraphStateStore.SuspendedContext;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import com.wechatai.common.model.ConversationKey;
import com.wechatai.proactive.path.BehaviorPathService;
import com.wechatai.session.entity.MessageEntity;
import com.wechatai.session.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * AI 引擎桥梁 — 封装 LangGraph 编译图调用 + 图中断/恢复 + 对话上下文管理 + 用户级线程池。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>用户发消息 → {@link #think(String, String, String)}</li>
 *   <li>检查 {@link GraphStateStore} 是否有挂起的中断 → 有则走确认/取消</li>
 *   <li>否则调用 {@link CompiledGraph#invoke(Map, RunnableConfig)}，threadId=userId</li>
 *   <li>图正常结束 → 发回复</li>
 *   <li>图被中断 → 检测 WRITE 工具 → 挂起等待用户确认 / READ 工具自动恢复</li>
 * </ol>
 */
@Slf4j
@Service
public class WechatChatBridge {

    private final CompiledGraph<ChatGraphState> compiledGraph;
    private final WechatOutboundSender sender;
    private final GraphStateStore graphStateStore;
    private final GraphInterruptHandler interruptHandler;
    private final MessageMapper messageMapper;
    private final RunWatchdog watchdog;
    private final ObjectMapper objectMapper;
    private final BehaviorPathService behaviorPathService;
    private final ParallelAgentBatchService parallelBatchService;

    /** 按用户缓存对话历史，key=微信用户ID，value=消息历史列表 */
    private final Map<String, List<Map<String, Object>>> userContexts = new ConcurrentHashMap<>();

    /** 每个用户一个单线程执行器，保证同用户消息按顺序处理 */
    private final Map<String, ExecutorService> userExecutors = new ConcurrentHashMap<>();

    private final AgentGraphRunner agentGraphRunner;

    public WechatChatBridge(CompiledGraph<ChatGraphState> compiledGraph,
                            WechatOutboundSender sender,
                            GraphStateStore graphStateStore,
                            GraphInterruptHandler interruptHandler,
                            MessageMapper messageMapper,
                            AgentGraphRunner agentGraphRunner,
                            RunWatchdog watchdog,
                            BehaviorPathService behaviorPathService,
                            ParallelAgentBatchService parallelBatchService) {
        this.compiledGraph = compiledGraph;
        this.sender = sender;
        this.graphStateStore = graphStateStore;
        this.interruptHandler = interruptHandler;
        this.messageMapper = messageMapper;
        this.parallelBatchService = parallelBatchService;
        this.agentGraphRunner = agentGraphRunner;
        this.watchdog = watchdog;
        this.objectMapper = new ObjectMapper();
        this.behaviorPathService = behaviorPathService;
    }

    /**
     * 获取用户专属的单线程执行器（不存在则创建）。
     * 包级可见 — 供 WechatInboundHandler.handleFile 提交耗时任务。
     */
    ExecutorService getExecutor(String userId) {
        return userExecutors.computeIfAbsent(userId, k ->
                Executors.newSingleThreadExecutor(r -> new Thread(r, "ai-" + userId.substring(0, Math.min(8, userId.length())))));
    }

    /**
     * AI 思考入口 — 提交到用户线程池异步执行，包总超时壳。
     */
    public void think(String fromUser, String content, String messageType) {
        getExecutor(fromUser).submit(() -> doThinkWithTimeout(fromUser, content, messageType));
    }

    /**
     * AI 思考入口 — 由调用方保证已在用户线程上执行，不再包装线程池。
     */
    public void thinkOnCurrentThread(String fromUser, String content, String messageType) {
        doThinkWithTimeout(fromUser, content, messageType);
    }

    // ========================================================================
    // 多账号适配层 — conversationKey = clientId:fromUserId
    // 提取 fromUserId 后委托给现有方法，上下文键保持 fromUserId（Phase 1 实现）
    // ========================================================================

    /**
     * 多账号 AI 思考入口 — 接受复合 conversationKey（clientId:fromUserId），
     * 提取纯 userId 后委托给现有的 {@link #think(String, String, String)}。
     */
    public void thinkByKey(String conversationKey, String content, String messageType) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        think(fromUser, content, messageType);
    }

    /**
     * 多账号 AI 思考入口（当前线程）— 同上，但不包装线程池。
     */
    public void thinkByKeyOnCurrentThread(String conversationKey, String content, String messageType) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        thinkOnCurrentThread(fromUser, content, messageType);
    }


    // ========================================================================
    // 核心逻辑
    // ========================================================================

    /**
     * 给 {@link #doThink} 加总超时壳。
     * <p>
     * 正常完成原样通过；超时 → 发提示给用户，释放用户线程。
     */
    private void doThinkWithTimeout(String fromUser, String content, String messageType) {
        try {
            watchdog.runWithTimeout(
                    () -> doThink(fromUser, content, messageType),
                    watchdog.getBridgeTimeoutMs(),
                    "Bridge." + fromUser);
        } catch (TimeoutException e) {
            log.warn("【Bridge】用户 {} 总超时 ({}ms)", fromUser,
                    watchdog.getBridgeTimeoutMs());
            sender.sendReply(fromUser, "处理超时，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private void doThink(String fromUser, String content, String messageType) {
        try {
            // ===== 行为路径自学习：采集上轮反馈 =====
            behaviorPathService.collectFeedback(fromUser, content);

            // ===== A. 检查是否有挂起的子 Agent WRITE 确认 =====
            if (interruptHandler.isConfirm(content) || interruptHandler.isCancel(content)) {
                if (handleSubAgentConfirm(fromUser, content, messageType)) {
                    return; // 已处理子 Agent 确认
                }
            }

            // ===== B. 检查是否有挂起的中央图确认 =====
            SuspendedContext ctx = graphStateStore.getSuspended(fromUser);
            if (ctx != null) {
                handleSuspendedResponse(fromUser, content, ctx);
                return;
            }

            // ===== B. 正常流程：调用编译图 =====
            // 使用 userId 作为 threadId，MemorySaver 检查点按用户复用。
            // 每个用户始终保持唯一一个 checkpoint，新图调用覆盖旧检查点，不会无限增长。
            String threadId = fromUser;

            // B1. 清理孤立 tool_call：assistant 的 tool_calls 后没有跟 tool 消息 → 删掉
            // 否则 DeepSeek API 会报 "insufficient tool messages following tool_calls message"
            List<Map<String, Object>> history = userContexts.get(fromUser);
            if (history != null) {
                history = cleanupOrphanedToolCalls(history);
                userContexts.put(fromUser, history);
            }

            Map<String, Object> initData = new HashMap<>();
            initData.put(ChatGraphState.KEY_USER_ID, fromUser);
            initData.put(ChatGraphState.KEY_USER_MESSAGE, content);
            initData.put(ChatGraphState.KEY_MESSAGE_TYPE, messageType);
            initData.put(ChatGraphState.KEY_CHANNEL, com.wechatai.common.enums.Channel.WECHAT.name());
            initData.put("messageHistory", history);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            var result = compiledGraph.invoke(initData, config);

            if (result.isPresent()) {
                ChatGraphState state = result.get();
                Map<String, Object> rawData = state.data();

                String reply = state.getReply();
                Object rawToolReqs = rawData.get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                boolean hasPendingTools = rawToolReqs != null
                        && (rawToolReqs instanceof List<?>)
                        && !((List<?>) rawToolReqs).isEmpty();

                log.info("【invoke 返回】reply='{}', hasPendingTools={}, finishReason={}, dataKeys={}",
                        reply != null ? reply.substring(0, Math.min(reply.length(), 60)) : "null",
                        hasPendingTools,
                        rawData.get(ChatGraphState.KEY_FINISH_REASON),
                        rawData.keySet());

                // ===== C. 图中断检测 =====
                String finishReason = (String) rawData.get(ChatGraphState.KEY_FINISH_REASON);
                if (hasPendingTools && "TOOL".equals(finishReason)) {
                    handleInterrupt(fromUser, threadId);
                    return;
                }

                // ===== D. 图正常完成 =====
                syncUserContexts(fromUser, state);

                // 发送工具产生的文件附件（截图等），在文字回复之前发送
                sendToolAttachments(fromUser, state);

                if (reply != null && !reply.isEmpty()) {
                    sender.sendReply(fromUser, reply);
                }
                graphStateStore.remove(fromUser);

            } else {
                // ===== E. invoke 返回空，一定是图中断 =====
                handleInterrupt(fromUser, threadId);
            }

        } catch (Exception e) {
            log.error("AI 处理失败 fromUser={}", fromUser, e);
            sender.sendError(fromUser);
        }
    }

    // ========================================================================
    // 挂起确认处理
    // ========================================================================

    /**
     * 用户发送的消息在挂起状态下 → 判断是确认还是取消。
     */
    /**
     * 处理子 Agent WRITE 工具的确认/取消。
     * 从消息历史中查找最近一次 needsConfirm 的工具结果，提取 threadId，
     * 调用 AgentGraphRunner.confirmResume() 恢复子 Agent 执行。
     *
     * @return true 表示已处理（不需要继续走图），false 表示没找到挂起的确认
     */
    @SuppressWarnings("unchecked")
    private boolean handleSubAgentConfirm(String fromUser, String content, String messageType) {
        // 并行批次：按队头确认（threadId 索引）
        ConfirmItem batchHead = parallelBatchService.peekConfirm(fromUser);
        String confirmThreadId = batchHead != null ? batchHead.threadId : null;

        if (confirmThreadId == null) {
            List<Map<String, Object>> history = userContexts.get(fromUser);
            if (history == null || history.isEmpty()) return false;
            for (int i = history.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = history.get(i);
                if (!"tool".equals(msg.get("role"))) continue;
                String toolContent = (String) msg.get("content");
                if (toolContent == null || !toolContent.contains("\"needsConfirm\":true")) continue;
                try {
                    Map<String, Object> confirmData = objectMapper.readValue(toolContent, Map.class);
                    confirmThreadId = (String) confirmData.get("threadId");
                    break;
                } catch (Exception ignored) {}
            }
        }
        if (confirmThreadId == null) return false;

        // 也可能批次 key 是 sessionId，用 threadId 反查
        var batchByThread = parallelBatchService.getByThreadId(confirmThreadId);
        String batchKey = batchByThread != null ? batchByThread.sessionId : fromUser;
        if (batchHead == null && batchByThread != null) {
            batchHead = parallelBatchService.peekConfirm(batchKey);
            if (batchHead != null) confirmThreadId = batchHead.threadId;
        }

        boolean confirmed = interruptHandler.isConfirm(content);
        log.info("【子Agent确认】用户 {} {} 子Agent WRITE, threadId={} parallel={}",
                fromUser, confirmed ? "确认" : "取消", confirmThreadId, batchByThread != null);

        AgentResult agentResult = agentGraphRunner.confirmResume(confirmThreadId, confirmed);

        if (parallelBatchService.getBySession(batchKey) != null) {
            ResumeOutcome outcome = parallelBatchService.onResumeResult(batchKey, agentResult);
            if (outcome.kind == ResumeOutcome.Kind.NEXT_CONFIRM) {
                sender.sendReply(fromUser, ParallelAgentBatchService.formatConfirmMessage(
                        outcome.nextConfirm, outcome.remainingConfirms));
                return true;
            }
            if (outcome.kind == ResumeOutcome.Kind.FINISHED) {
                for (Map<String, Object> att : parallelBatchService.allAttachments(outcome.batch)) {
                    String filePath = (String) att.get("filePath");
                    if (filePath != null && "image".equals(att.get("type"))) {
                        sender.sendImage(fromUser, filePath);
                    }
                }
                String summary = parallelBatchService.aggregate(outcome.batch);
                parallelBatchService.clear(batchKey);
                sender.sendReply(fromUser, summary != null && !summary.isBlank()
                        ? summary : (agentResult.getSummary() != null ? agentResult.getSummary() : "已完成"));
                return true;
            }
        }

        if (agentResult.isOk()) {
            java.util.List<java.util.Map<String, Object>> attachments = agentResult.getAttachments();
            if (attachments != null) {
                for (java.util.Map<String, Object> att : attachments) {
                    String filePath = (String) att.get("filePath");
                    if (filePath != null && "image".equals(att.get("type"))) {
                        sender.sendImage(fromUser, filePath);
                    }
                }
            }
            sender.sendReply(fromUser, agentResult.getSummary());
        } else if (agentResult.needsConfirm()) {
            sender.sendReply(fromUser, agentResult.getSummary());
        } else {
            sender.sendReply(fromUser, agentResult.getError() != null
                    ? agentResult.getError() : "操作失败，请重试");
        }
        return true;
    }

    private void handleSuspendedResponse(String fromUser, String content, SuspendedContext ctx) {
        if (interruptHandler.isConfirm(content)) {
            log.info("【用户确认】{} 确认执行 {}, 恢复图执行", fromUser, ctx.getToolName());
            resumeGraph(ctx.getThreadId(), fromUser);
        } else if (interruptHandler.isCancel(content)) {
            log.info("【用户取消】{} 取消执行 {}", fromUser, ctx.getToolName());
            cancelGraph(fromUser, ctx);
        } else {
            // 既不是确认也不是取消 → 提示用户
            sender.sendReply(fromUser,
                    "请回复「确认」执行或「取消」取消" + ctx.getToolName() + "操作");
        }
    }

    // ========================================================================
    // 中断处理
    // ========================================================================

    /**
     * 图中断后处理：检查待执行的工具中是否有 WRITE 类型。
     * <p>
     * WRITE → 挂起等待用户确认。<br>
     * 纯 READ → 自动恢复执行。
     */
    @SuppressWarnings("unchecked")
    private void handleInterrupt(String fromUser, String threadId) {
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            // 从 MemorySaver 获取中断时的状态快照
            var snapshot = compiledGraph.getState(config);
            if (snapshot == null) {
                log.warn("【中断】无法获取中断状态快照 fromUser={}", fromUser);
                sender.sendError(fromUser);
                return;
            }

            ChatGraphState state = snapshot.state();
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                    state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);

            if (toolCalls == null || toolCalls.isEmpty()) {
                log.warn("【中断】中断时未找到待执行的工具调用 fromUser={}", fromUser);
                // 没有工具调用 → 可能是其他原因中断，直接恢复试试
                resumeGraph(threadId, fromUser);
                return;
            }

            String writeTool = interruptHandler.findWriteToolName(toolCalls);
            if (writeTool != null) {
                // 有 WRITE 工具 → 挂起，等用户确认
                String args = extractArgs(toolCalls, writeTool);
                String sessionId = state.getSessionId();

                SuspendedContext ctx = new SuspendedContext(
                        fromUser, sessionId, threadId,
                        writeTool, args, System.currentTimeMillis());
                graphStateStore.suspend(fromUser, ctx);

                // 对于打车下单，从 messageHistory 中提取起终点和价格信息
                String confirmMsg;
                if ("taxi_create_order".equals(writeTool)) {
                    confirmMsg = buildTaxiConfirmMessage(state, args);
                } else {
                    confirmMsg = interruptHandler.buildConfirmMessage(writeTool, args);
                }
                sender.sendReply(fromUser, confirmMsg);
                log.info("【图中断】用户 {} 工具 {} 等待确认", fromUser, writeTool);

            } else {
                // 只有 READ 工具 → 自动放行，恢复图执行
                log.info("【图中断】纯 READ 工具 fromUser={}，自动恢复", fromUser);
                resumeGraph(threadId, fromUser);
            }

        } catch (Exception e) {
            log.error("【中断处理】异常 fromUser={}", fromUser, e);
            sender.sendError(fromUser);
        }
    }

    // ========================================================================
    // 恢复 / 取消
    // ========================================================================

    /**
     * 恢复被中断的图执行。
     * <p>
     * 调用 {@code invoke(null, config)} 让图从上一次中断点继续执行。
     * 恢复后可能再次中断（LLM 调完一个工具后又调另一个），循环处理直到图真正结束。
     * READ 工具自动恢复，WRITE 工具挂起等用户确认。
     */
    private void resumeGraph(String threadId, String fromUser) {
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            // 循环恢复：LLM 可能链式调用多个工具，每个工具执行后可能再次中断
            int maxResume = 10;
            for (int i = 0; i < maxResume; i++) {
                var result = compiledGraph.invoke((Map<String, Object>) null, config);

                if (result.isEmpty()) {
                    // invoke 返回空 → 图中断
                    log.warn("【恢复】第{}次恢复后中断 fromUser={}", i + 1, fromUser);
                    handleInterrupt(fromUser, threadId);
                    return;
                }

                ChatGraphState state = result.get();
                syncUserContexts(fromUser, state);

                String finishReason = (String) state.data().get(ChatGraphState.KEY_FINISH_REASON);
                Object rawToolReqs = state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                boolean hasPendingTools = rawToolReqs != null
                        && (rawToolReqs instanceof List<?>)
                        && !((List<?>) rawToolReqs).isEmpty();

                if (!hasPendingTools || !"TOOL".equals(finishReason)) {
                    // 图真正结束 → 发附件 + 回复
                    sendToolAttachments(fromUser, state);
                    String reply = state.getReply();
                    if (reply != null && !reply.isEmpty()) {
                        sender.sendReply(fromUser, reply);
                    }
                    graphStateStore.remove(fromUser);
                    log.info("【恢复】用户 {} 图执行完成", fromUser);
                    return;
                }

                // 再次中断，检查是 READ 还是 WRITE
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                        rawToolReqs;
                String writeTool = interruptHandler.findWriteToolName(toolCalls);
                if (writeTool != null) {
                    // 有 WRITE → 挂起等用户确认
                    handleInterrupt(fromUser, threadId);
                    return;
                }

                // 纯 READ → 继续循环 resume
                log.info("【恢复】第{}次恢复后再次触发中断(READ)，自动继续 threadId={}",
                        i + 1, threadId);
            }

            log.error("【恢复】超过最大恢复次数({}) fromUser={}", maxResume, fromUser);
            sender.sendError(fromUser);
            graphStateStore.remove(fromUser);

        } catch (Exception e) {
            log.error("【恢复】图恢复失败 fromUser={}", fromUser, e);
            sender.sendError(fromUser);
            graphStateStore.remove(fromUser);
        }
    }

    /**
     * 发送工具执行产生的附件（截图等）给用户。
     * <p>
     * 从 state 的 toolAttachments 字段读取附件列表，目前支持 image 类型。
     */
    @SuppressWarnings("unchecked")
    private void sendToolAttachments(String fromUser, ChatGraphState state) {
        List<Map<String, Object>> attachments = state.getToolAttachments();
        if (attachments == null || attachments.isEmpty()) return;

        for (Map<String, Object> att : attachments) {
            String type = (String) att.get("type");
            String filePath = (String) att.get("filePath");
            if (filePath == null) continue;

            log.info("【发送附件】type={}, filePath={}", type, filePath);

            if ("image".equals(type)) {
                sender.sendImage(fromUser, filePath);
            } else if ("file".equals(type)) {
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get(filePath);
                    if (!java.nio.file.Files.isRegularFile(p)) {
                        log.warn("【发送附件】文件不存在: {}", filePath);
                        continue;
                    }
                    byte[] data = java.nio.file.Files.readAllBytes(p);
                    String name = p.getFileName().toString();
                    String ext = "";
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0 && dot < name.length() - 1) {
                        ext = name.substring(dot + 1);
                    }
                    sender.sendFile(fromUser, data, name, ext);
                } catch (Exception e) {
                    log.warn("【发送附件】发送文件失败: {}", e.getMessage());
                }
            } else {
                log.warn("【发送附件】未知附件类型: {}", type);
            }
        }
    }

    /**
     * 取消被中断的图执行（用户取消后）。
     * <p>
     * 关键处理：
     * <ol>
     *   <li>从 toolExecutionRequests 提取 LLM 返回的真实 tool_call_id，用真实 ID 注入取消结果</li>
     *   <li>同步写一条 TOOL_RESULT 到 message 表，修复 DB 中 assistant(tool_calls) 无对应 tool_result 的断裂</li>
     *   <li>否则 DeepSeek 下次加载历史时会报 "tool_call_ids did not have response messages" 400 错误</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private void cancelGraph(String fromUser, SuspendedContext ctx) {
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(ctx.getThreadId())
                    .build();

            // 1. 获取当前 checkpoint 状态
            var snapshot = compiledGraph.getState(config);
            if (snapshot != null) {
                ChatGraphState state = snapshot.state();
                List<Map<String, Object>> messages = (List<Map<String, Object>>)
                        state.data().get("messageHistory");
                if (messages == null) messages = new ArrayList<>();

                String sessionId = ctx.getSessionId();

                // 2. 从 toolExecutionRequests 提取 LLM 返回的真实 tool_call_id
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>)
                        state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);

                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (Map<String, Object> tc : toolCalls) {
                        String realId = (String) tc.get("id");
                        Map<String, Object> func = (Map<String, Object>) tc.get("function");
                        String toolName = func != null ? (String) func.get("name") : ctx.getToolName();
                        String argsJson = func != null ? (String) func.get("arguments") : "{}";

                        // 2a. 注入一条带真实 tool_call_id 的取消结果到 messageHistory
                        Map<String, Object> cancelResult = new LinkedHashMap<>();
                        cancelResult.put("role", "tool");
                        cancelResult.put("tool_call_id", realId);
                        cancelResult.put("content", "用户已取消此操作");
                        messages.add(cancelResult);

                        // 2b. 同步写 TOOL_RESULT 到 message 表，修复 DB 断裂
                        saveCancelResult(sessionId, realId, toolName, argsJson);
                    }
                } else {
                    // 兜底：没有待执行工具，清理孤立 tool_calls
                    log.warn("【取消】未找到 toolExecutionRequests，清理孤立 tool_calls");
                    messages = cleanupOrphanedToolCalls(messages);
                }

                // 3. 更新 checkpoint：清空待执行工具 + 注入取消消息
                Map<String, Object> updates = new HashMap<>();
                updates.put("messageHistory", messages);
                updates.put(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS, new ArrayList<>());
                updates.put(ChatGraphState.KEY_NEED_TOOL, false);

                compiledGraph.updateState(config, updates);
            }

            // 4. 恢复图（tool_execute 无事可做 → llm_think 生成回复）
            var result = compiledGraph.invoke((Map<String, Object>) null, config);

            if (result.isPresent()) {
                ChatGraphState state = result.get();
                syncUserContexts(fromUser, state);

                // 如果 LLM 取消后仍调用了工具（如"发个链接"），清理掉防止 DB 链断裂
                Object rawToolReqs = state.data().get(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS);
                boolean hasPendingTools = rawToolReqs != null
                        && (rawToolReqs instanceof List<?>)
                        && !((List<?>) rawToolReqs).isEmpty();
                if (hasPendingTools) {
                    log.warn("【取消】取消后 LLM 仍调用了工具，清理残留 tool_calls");
                    Map<String, Object> cleanUpdates = new HashMap<>();
                    cleanUpdates.put(ChatGraphState.KEY_TOOL_EXECUTION_REQUESTS, new ArrayList<>());
                    cleanUpdates.put(ChatGraphState.KEY_NEED_TOOL, false);
                    compiledGraph.updateState(config, cleanUpdates);

                    // 再 invoke 一次得到纯回复
                    var cleanResult = compiledGraph.invoke((Map<String, Object>) null, config);
                    if (cleanResult.isPresent()) {
                        syncUserContexts(fromUser, cleanResult.get());
                    }
                }
            }

            // 5. 告知用户已取消
            graphStateStore.remove(fromUser);
            sender.sendReply(fromUser, "已取消" + ctx.getToolName() + "操作");
            log.info("【取消】用户 {} 取消执行 {}", fromUser, ctx.getToolName());

        } catch (Exception e) {
            log.error("【取消】处理失败 fromUser={}", fromUser, e);
            graphStateStore.remove(fromUser);
            sender.sendReply(fromUser, "已取消" + ctx.getToolName() + "操作");
        }
    }

    /**
     * 将用户取消操作写入 message 表，修复 DB 中断裂（assistant 有 tool_calls 但无对应 tool_result）。
     */
    private void saveCancelResult(String sessionId, String toolCallId, String toolName, String argsJson) {
        if (sessionId == null) return;
        try {
            MessageEntity entity = new MessageEntity();
            entity.setMessageId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            entity.setRole(MessageRole.TOOL);
            entity.setMessageType(MessageType.TOOL_RESULT);
            entity.setContent("{\"error\": \"用户已取消操作\"}");
            entity.setToolCalls(objectMapper.writeValueAsString(Map.of(
                    "tool_call_id", toolCallId,
                    "name", toolName,
                    "arguments", argsJson,
                    "canceled", true
            )));
            messageMapper.insert(entity);
            log.info("【取消】已写入 TOOL_RESULT 到 message 表 toolName={}, tool_call_id={}", toolName, toolCallId);
        } catch (Exception e) {
            log.warn("【取消】写入 message 表失败: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 将图执行后的 messageHistory 同步回 userContexts 缓存。
     */
    @SuppressWarnings("unchecked")
    private void syncUserContexts(String fromUser, ChatGraphState state) {
        Object updatedHistory = state.data().get("messageHistory");
        if (updatedHistory instanceof List) {
            userContexts.put(fromUser, (List<Map<String, Object>>) updatedHistory);
        }
    }

    /**
     * 构建打车下单的确认消息，从 messageHistory 中提取起终点和价格信息。
     */
    @SuppressWarnings("unchecked")
    private String buildTaxiConfirmMessage(ChatGraphState state, String argsJson) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>)
                state.data().get("messageHistory");
        String originName = "?";
        String destName = "?";
        String priceInfo = "";
        String productCategory = "";

        // 1. 提取当前订单的车型
        try {
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            productCategory = args.getOrDefault("product_category", "").toString();
        } catch (Exception ignored) {}

        // 2. 从 messageHistory 中查找信息（两遍扫描避免 break 漏查）
        if (messages != null) {
            // 第一遍：从后往前找 taxi_estimate 的起终点（不 break）
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if (!"assistant".equals(msg.get("role"))) continue;
                Object tcs = msg.get("tool_calls");
                if (!(tcs instanceof List)) continue;
                for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    if (func == null) continue;
                    if (!"taxi_estimate".equals(func.get("name"))) continue;
                    String argsStr = (String) func.get("arguments");
                    if (argsStr == null) continue;
                    try {
                        Map<String, Object> estimateArgs = objectMapper.readValue(argsStr, Map.class);
                        String fn = (String) estimateArgs.get("from_name");
                        String tn = (String) estimateArgs.get("to_name");
                        if (fn != null && !fn.equals("?")) originName = fn;
                        if (tn != null && !tn.equals("?")) destName = tn;
                    } catch (Exception ignored) {}
                }
            }
            // 第二遍：从后往前找最近的 taxi_estimate 价格信息（找到 break）
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                String content = (String) msg.get("content");
                if (content == null || !content.contains("网约车价格预估结果")) continue;
                int priceStart = content.indexOf("网约车价格预估结果");
                int priceEnd = content.indexOf("预估流程ID", priceStart);
                if (priceEnd < 0) priceEnd = content.length();
                priceInfo = content.substring(priceStart, priceEnd).trim();
                break;
            }
        }

        // 3. 车型映射
        String carType = switch (productCategory) {
            case "191" -> "惊喜特价";
            case "201" -> "特惠快车";
            case "1" -> "快车";
            case "193" -> "滴滴轻享";
            case "8" -> "专车";
            case "9" -> "六座专车";
            case "17" -> "豪华车";
            default -> "车型代码 " + productCategory;
        };

        // 4. 组装确认消息
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 需要你确认以下操作：\n\n");
        sb.append("🚗 打车下单\n");
        sb.append("  起点：").append(originName).append("\n");
        sb.append("  终点：").append(destName).append("\n");
        sb.append("  车型：").append(carType).append("\n");
        if (!priceInfo.isEmpty()) {
            // 只显示选中车型的价格那一行
            String[] lines = priceInfo.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.contains(carType) || trimmed.endsWith(productCategory)) {
                    sb.append("  预估价格：").append(trimmed).append("\n");
                    break;
                }
            }
        }
        sb.append("\n回复「确认」或「是」下单，回复「取消」或「否」取消");
        return sb.toString();
    }

    /**
     * 从 toolCalls 列表中提取指定工具的参数 JSON。
     */
    @SuppressWarnings("unchecked")
    private String extractArgs(List<Map<String, Object>> toolCalls, String toolName) {
        if (toolCalls == null) return "{}";
        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            if (func != null && toolName.equals(func.get("name"))) {
                String args = (String) func.get("arguments");
                return args != null ? args : "{}";
            }
        }
        return "{}";
    }

    /**
     * 双向清理孤立消息，确保 DeepSeek API 格式合法。
     * <p>
     * 规则：
     * <ol>
     *   <li>assistant 有 tool_calls 但下一条不是 tool → 删 tool_calls</li>
     *   <li>tool 消息的 tool_call_id 没有对应的 assistant(tool_calls) → 删该 tool 消息</li>
     *   <li>连续重复 role 的 assistant 消息（无 tool_calls）→ 去重，只保留最后一条</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cleanupOrphanedToolCalls(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return history;

        List<Map<String, Object>> cleaned = new ArrayList<>(history);

        // Pass 1: 收集所有合法的 tool_call_id（assistant(tc) 后紧跟 tool 消息）
        Set<String> validToolCallIds = new LinkedHashSet<>();
        for (int i = 0; i < cleaned.size() - 1; i++) {
            Map<String, Object> curr = cleaned.get(i);
            Map<String, Object> next = cleaned.get(i + 1);
            if ("assistant".equals(curr.get("role")) && curr.containsKey("tool_calls")
                    && "tool".equals(next.get("role"))) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) curr.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        Object id = tc.get("id");
                        if (id != null) validToolCallIds.add(id.toString());
                    }
                }
            }
        }

        // Pass 2: 清理
        int removed = 0;
        for (int i = cleaned.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = cleaned.get(i);

            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                boolean hasFollowingTool = i + 1 < cleaned.size()
                        && "tool".equals(cleaned.get(i + 1).get("role"));
                if (!hasFollowingTool) {
                    Map<String, Object> fixed = new LinkedHashMap<>(msg);
                    fixed.remove("tool_calls");
                    cleaned.set(i, fixed);
                    removed++;
                }
            } else if ("tool".equals(msg.get("role"))) {
                String tcid = (String) msg.get("tool_call_id");
                if (tcid != null && !validToolCallIds.contains(tcid)) {
                    cleaned.remove(i);
                    removed++;
                }
            }
        }

        // Pass 3: 去重重连续 assistant（无 tool_calls）— 只保留最后一条
        for (int i = cleaned.size() - 2; i >= 0; i--) {
            Map<String, Object> curr = cleaned.get(i);
            Map<String, Object> next = cleaned.get(i + 1);
            if ("assistant".equals(curr.get("role")) && !curr.containsKey("tool_calls")
                    && "assistant".equals(next.get("role")) && !next.containsKey("tool_calls")) {
                cleaned.remove(i);
                removed++;
            }
        }

        if (removed > 0) {
            log.info("【清理】移除 {} 条损坏/冗余消息，剩余 {} 条", removed, cleaned.size());
        }
        return cleaned;
    }
}
