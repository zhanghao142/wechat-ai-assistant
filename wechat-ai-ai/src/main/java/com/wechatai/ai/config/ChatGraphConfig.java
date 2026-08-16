package com.wechatai.ai.config;

import com.wechatai.ai.agent.AgentGraphRunner;
import com.wechatai.ai.agent.ParallelAgentBatchService;
import com.wechatai.ai.ai.LlmService;
import com.wechatai.ai.ai.RunWatchdog;
import com.wechatai.ai.node.LlmThinkNode;
import com.wechatai.ai.node.PrepareInputNode;
import com.wechatai.ai.node.SessionPrepareNode;
import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.ai.node.ToolExecuteNode;
import com.wechatai.ai.service.DesktopProgressEmitter;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.session.mapper.MessageMapper;
import com.wechatai.session.service.SessionService;
import com.wechatai.tool.registry.AgentToolRegistry;
import com.wechatai.tool.registry.ToolRegistry;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * LangGraph 图定义 — 对齐 demo {@code AgentConfig} 写法。
 * <p>
 * START → prepare_input → session_prepare → llm_think
 *       → [need_tool ? tool_execute → llm_think : END]
 * <p>
 * 提供两个 Bean：
 * <ul>
 *   <li>{@link #simpleChatGraph} — 图的结构定义（节点 + 边）</li>
 *   <li>{@link #compiledChatGraph} — 编译后的图实例，注入 MemorySaver + interruptBefore 实现图中断</li>
 * </ul>
 */
@Configuration
public class ChatGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatGraphConfig.class);

    /**
     * ReAct 图（含 session_prepare 节点）。
     * START → prepare_input → session_prepare → llm_thi  nk
     *       → [needTool ? tool_execute → llm_think : END]
     */
    @Bean
    public StateGraph<ChatGraphState> simpleChatGraph(SessionService sessionService,
                                                       MessageMapper messageMapper,
                                                       @Lazy ToolRegistry toolRegistry,
                                                       AgentToolRegistry agentToolRegistry,
                                                       AgentGraphRunner agentGraphRunner,
                                                       LlmService llmService,
                                                       RunWatchdog watchdog,
                                                       UsageMetricsService usageMetricsService,
                                                       ParallelAgentBatchService parallelBatchService,
                                                       DesktopProgressEmitter progressEmitter,
                                                       @Value("${mcp.filesystem.allowed-dir:./upload}") String sandboxDir,
                                                       @Value("${docgen.storage-path:./AItext}") String docgenDir)
            throws GraphStateException {

        PrepareInputNode prepareInputNode = new PrepareInputNode();
        SessionPrepareNode sessionPrepareNode = new SessionPrepareNode(sessionService, messageMapper);
        LlmThinkNode llmThinkNode = new LlmThinkNode(llmService);
        ToolExecuteNode toolExecuteNode = new ToolExecuteNode(
                toolRegistry, agentToolRegistry, agentGraphRunner, messageMapper, watchdog, sandboxDir,
                docgenDir, usageMetricsService, parallelBatchService, progressEmitter);

        return new StateGraph<>(ChatGraphState::new)
                .addNode("prepare_input", withStateLog("prepare_input", prepareInputNode))
                .addNode("session_prepare", withStateLog("session_prepare", sessionPrepareNode))
                .addNode("llm_think", withStateLog("llm_think", llmThinkNode))
                .addNode("tool_execute", withStateLog("tool_execute", toolExecuteNode))
                .addEdge(START, "prepare_input")
                .addEdge("prepare_input", "session_prepare")
                .addEdge("session_prepare", "llm_think")
                .addConditionalEdges("llm_think",
                        state -> {
                            String route = state.isNeedTool() ? "tool" : "done";
                            log.debug("【llm_think 路由】needTool={} → {}", state.isNeedTool(), route);
                            return CompletableFuture.completedFuture(route);
                        },
                        Map.of("tool", "tool_execute", "done", END))
                .addConditionalEdges("tool_execute",
                        state -> {
                            boolean await = Boolean.TRUE.equals(
                                    state.data().get(ChatGraphState.KEY_AWAIT_PARALLEL_CONFIRM));
                            String route = await ? "confirm" : "llm";
                            log.debug("【tool_execute 路由】awaitParallelConfirm={} → {}", await, route);
                            return CompletableFuture.completedFuture(route);
                        },
                        Map.of("llm", "llm_think", "confirm", END));
    }

    /**
     * 编译后的图实例 — 无图中断，一次 invoke 跑完整个 ReAct 循环。
     * <p>
     * 中央 Orchestrator 只分发 Agent 工具（READ），不需要 WRITE 确认。
     * WRITE 确认下沉到子 Agent 图中。
     */
    @Bean
    public CompiledGraph<ChatGraphState> compiledChatGraph(
            StateGraph<ChatGraphState> simpleChatGraph) throws GraphStateException {
        MemorySaver saver = new MemorySaver();
        CompileConfig config = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();
        log.info("✅ 编译图完成 (无中断, saver=MemorySaver)");
        return simpleChatGraph.compile(config);
    }

    private AsyncNodeAction<ChatGraphState> withStateLog(String nodeName, AsyncNodeAction<ChatGraphState> action) {
        return state -> action.apply(state).thenApply(update -> {
            Map<String, Object> merged = new HashMap<>(state.data());
            merged.putAll(update);
            log.debug("【{} 执行后状态】{}", nodeName, merged);
            return update;
        });
    }
}
