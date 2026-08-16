package com.wechatai.agent;

import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 子 Agent 统一调用门面 — 自包含小 ReAct 循环。
 * <p>
 * 调 LLM（只传工具白名单）→ 执行工具 → 回填结果 → 继续循环。
 * 中央 Orchestrator 得到最终摘要，无需关心中间过程。
 */
@Component
public class AgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(AgentInvoker.class);

    private final LlmCaller llmCaller;
    private final ToolRegistry toolRegistry;

    public AgentInvoker(LlmCaller llmCaller, @Lazy ToolRegistry toolRegistry) {
        this.llmCaller = llmCaller;
        this.toolRegistry = toolRegistry;
    }

    /**
     * @param agentId   Agent 标识（日志用）
     * @param task      中央改写后的子任务
     * @param tools     该 Agent 的工具白名单
     * @param maxRounds 最大 ReAct 轮数
     * @param timeoutMs 超时（毫秒）
     */
    public AgentResult invoke(String agentId, String task,
                              List<ToolDefinitionVO> tools,
                              int maxRounds, long timeoutMs) {
        long start = System.currentTimeMillis();
        int round = 0;

        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system",
                    "content", buildAgentSystemPrompt(agentId, tools)));
            messages.add(Map.of("role", "user", "content", task));

            while (round < maxRounds) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    return AgentResult.fail("任务超时", round, System.currentTimeMillis() - start);
                }

                Map<String, Object> llmResult = llmCaller.callLlm(messages, tools);
                String content = (String) llmResult.get("content");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls =
                        (List<Map<String, Object>>) llmResult.get("toolCalls");

                if (toolCalls == null || toolCalls.isEmpty()) {
                    return AgentResult.ok(content != null ? content : "",
                            round + 1, System.currentTimeMillis() - start);
                }

                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", content != null ? content : "");
                assistantMsg.put("tool_calls", toolCalls);
                messages.add(assistantMsg);

                for (Map<String, Object> tc : toolCalls) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    String toolName = (String) func.get("name");
                    String argsJson = (String) func.get("arguments");
                    String callId = (String) tc.get("id");

                    String resultJson = executeTool(toolName, argsJson);
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", resultJson
                    ));
                }
                round++;
            }

            return AgentResult.fail("任务超过最大轮数", round, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("【AgentInvoker】{} 异常", agentId, e);
            return AgentResult.fail(e.getMessage(), round, System.currentTimeMillis() - start);
        }
    }

    private String executeTool(String toolName, String argsJson) {
        if (toolRegistry.isExternalTool(toolName)) {
            return toolRegistry.executeExternalTool(toolName, argsJson);
        }
        for (Object bean : toolRegistry.getEnabledToolBeans()) {
            for (Method method : bean.getClass().getMethods()) {
                if (!method.getName().equals(toolName)) continue;
                if (method.getAnnotation(Tool.class) == null) continue;
                try {
                    Map<String, String> argMap = parseArgs(argsJson);
                    Object[] paramValues = buildParams(method, argMap);
                    Object result = method.invoke(bean, paramValues);
                    return result != null ? result.toString() : "{}";
                } catch (Exception e) {
                    log.warn("【AgentInvoker】工具 {} 执行失败: {}", toolName, e.getMessage());
                    return "{\"error\": \"" + e.getMessage() + "\"}";
                }
            }
        }
        return "{\"error\": \"未找到工具: " + toolName + "\"}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseArgs(String argsJson) {
        Map<String, String> argMap = new LinkedHashMap<>();
        if (argsJson == null || argsJson.isEmpty()) return argMap;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> raw = om.readValue(argsJson, Map.class);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                argMap.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : null);
            }
        } catch (Exception ignored) {}
        return argMap;
    }

    private Object[] buildParams(Method method, Map<String, String> argMap) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String raw = argMap.get(params[i].getName());
            Class<?> type = params[i].getType();
            if (raw == null) { values[i] = null; }
            else if (type == String.class) { values[i] = raw; }
            else if (type == int.class || type == Integer.class) { values[i] = Integer.parseInt(raw); }
            else if (type == long.class || type == Long.class) { values[i] = Long.parseLong(raw); }
            else if (type == double.class || type == Double.class) { values[i] = Double.parseDouble(raw); }
            else if (type == boolean.class || type == Boolean.class) { values[i] = Boolean.parseBoolean(raw); }
            else if (type == List.class || type == Set.class || type == Collection.class) {
                try { values[i] = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, List.class); }
                catch (Exception e) { values[i] = List.of(raw); }
            } else { values[i] = raw; }
        }
        return values;
    }

    private String buildAgentSystemPrompt(String agentId, List<ToolDefinitionVO> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是").append(agentId)
                .append("，专注于处理特定领域的任务。\n")
                .append("只使用给你分配的工具，不要尝试调用其他工具。\n")
                .append("返回简洁的结果摘要，不要长篇大论。\n\n")
                .append("你的工具：\n");
        for (ToolDefinitionVO t : tools) {
            sb.append("- ").append(t.getName()).append("：")
                    .append(t.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
