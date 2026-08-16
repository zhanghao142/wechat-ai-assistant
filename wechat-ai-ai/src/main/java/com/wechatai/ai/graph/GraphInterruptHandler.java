package com.wechatai.ai.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.common.enums.ToolCategory;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 图中断处理器 — 检测 WRITE 工具调用、组装确认消息、识别用户意图。
 * <p>
 * 与 {@link GraphStateStore} 配合，实现"WRITE 工具执行前暂停 + 用户确认/取消"流程。
 * <p>
 * 纯 READ 工具自动放行，不经过确认流程。
 */
@Component
public class GraphInterruptHandler {

    private static final Logger log = LoggerFactory.getLogger(GraphInterruptHandler.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public GraphInterruptHandler(@Lazy ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 检测 toolCalls 中是否有 WRITE 类型工具，返回第一个 WRITE 工具名。
     *
     * @param toolCalls LLM 返回的工具调用列表
     * @return WRITE 工具名，没有则返回 null
     */
    public String findWriteToolName(List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;

        List<ToolDefinitionVO> tools = toolRegistry.listTools(null, null);
        for (Map<String, Object> tc : toolCalls) {
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            if (func == null) continue;
            String name = (String) func.get("name");
            if (name == null) continue;

            for (ToolDefinitionVO def : tools) {
                if (name.equals(def.getName()) && def.getCategory() == ToolCategory.WRITE) {
                    log.info("【WRITE 检测】发现 WRITE 工具: {}", name);
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * 组装确认消息，展示给用户。
     *
     * @param toolName  工具名
     * @param argsJson  工具参数 JSON
     * @return 确认消息文本
     */
    public String buildConfirmMessage(String toolName, String argsJson) {
        return "⚠️ 需要你确认以下操作：\n\n"
                + "操作：" + toolName + "\n"
                + "详情：\n" + formatArgs(toolName, argsJson) + "\n\n"
                + "回复「确认」或「是」执行，回复「取消」或「否」取消";
    }

    /**
     * 判断用户消息是否为"确认"。
     */
    public boolean isConfirm(String msg) {
        if (msg == null) return false;
        String t = msg.trim();
        return t.equals("确认") || t.equals("是") || t.equals("确定")
                || t.equals("嗯") || t.equals("好") || t.equals("发")
                || t.startsWith("确认") || t.startsWith("是 ");
    }

    /**
     * 判断用户消息是否为"取消"。
     */
    public boolean isCancel(String msg) {
        if (msg == null) return false;
        String t = msg.trim();
        return t.equals("取消") || t.equals("否") || t.equals("不")
                || t.equals("算了") || t.equals("别")
                || t.startsWith("取消") || t.startsWith("否 ");
    }

    /**
     * 按工具名格式化参数，方便用户阅读。
     */
    private String formatArgs(String toolName, String argsJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            if (args == null || args.isEmpty()) return "（无参数）";

            return switch (toolName) {
                case "sendEmail" -> String.format(
                        "  收件人：%s\n  主题：%s\n  内容：%s",
                        args.getOrDefault("to", "?"),
                        args.getOrDefault("subject", "（无主题）"),
                        args.getOrDefault("body", "?"));
                case "generateDocument" -> String.format(
                        "  文件名：%s\n  内容长度：%d 字",
                        args.getOrDefault("fileName", "?"),
                        args.getOrDefault("content", "").toString().length());
                case "setRemind" -> String.format(
                        "  提醒内容：%s\n  时间：%s",
                        args.getOrDefault("content", "?"),
                        args.getOrDefault("remindAt", "?"));
                case "taxi_cancel_order" -> String.format(
                        "  订单号：%s\n  原因：%s",
                        args.getOrDefault("order_id", "?"),
                        args.getOrDefault("reason", "未指定"));
                default -> {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Object> e : args.entrySet()) {
                        sb.append("  ").append(e.getKey()).append("：").append(e.getValue()).append("\n");
                    }
                    yield sb.toString();
                }
            };
        } catch (JsonProcessingException e) {
            log.warn("格式化参数失败, toolName={}, argsJson={}", toolName, argsJson);
            return argsJson;
        }
    }
}
