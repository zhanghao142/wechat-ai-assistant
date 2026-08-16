package com.wechatai.tool.registry;

import com.wechatai.common.enums.ToolCategory;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.model.vo.ToolHistoryVO;

import java.util.List;

/**
 * 工具注册中心。
 * <p>
 * AI 是大脑、工具是手脚：扫描 Spring 容器中带 {@code @Tool} 的 Bean，
 * 供 AI 引擎同 JVM 反射调用（不走 HTTP）。{@code description} 质量直接影响模型选工具效果。
 * 运维接口可列出、启停工具。
 */
public interface ToolRegistry {

    /** 列出工具定义，可按分类筛选 */
    List<ToolDefinitionVO> listTools(String sessionId, ToolCategory category);

    /** 启停指定工具 */
    void toggle(String toolName, boolean enabled);

    /** 返回当前启用的工具 Bean，供 LangChain4j 绑定 */
    List<Object> getEnabledToolBeans();

    /** 记录工具调用历史 */
    void recordHistory(String toolCallId, String toolName, String arguments, boolean success, String resultSummary, long latencyMs);

    /** 获取指定会话的工具调用历史 */
    List<ToolHistoryVO> getHistory(String sessionId);

    // ── 外部工具支持（MCP 等） ──

    /**
     * 注册一个外部工具到工具注册中心（如 MCP 工具）。
     * <p>
     * 外部工具与本地 @Tool 在 LLM 视角下无差异，但执行时走对应的外部客户端而非反射。
     *
     * @param def    工具定义
     * @param source 来源标记，如 {@code "MCP:DIDI"}
     */
    void registerExternalTool(ToolDefinitionVO def, String source);

    /**
     * 检查指定工具是否为外部工具。
     * <p>
     * 在 {@code ToolExecuteNode} 中根据此判断选择执行路径。
     */
    boolean isExternalTool(String toolName);

    /**
     * 根据工具名获取其来源标记。
     * @return null=本地@Tool, "MCP:DIDI"=滴滴MCP, 等等
     */
    String getSource(String toolName);

    /**
     * 执行外部工具调用。
     * <p>
     * 委托给对应的外部客户端（如 McpClientManager），返回执行结果的 JSON 字符串。
     *
     * @param toolName  工具名
     * @param argsJson  参数字符串（JSON 格式）
     * @return 工具执行结果的 JSON 字符串
     */
    String executeExternalTool(String toolName, String argsJson);
}
