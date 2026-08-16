package com.wechatai.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具源抽象接口 — 对接任意 JSON-RPC 2.0 MCP Server。
 * <p>
 * 每个 MCP 源（滴滴出行、高德地图等）实现此接口，由 {@link McpClientManager} 统一管理。
 * 新增一个 MCP 源只需新建一个类实现此接口，用 {@code @Component} 注册即可。
 * <p>
 * 接口方法：
 * <ul>
 *   <li>{@link #getSourceName()} — 唯一来源标记，如 {@code "MCP:DIDI"}</li>
 *   <li>{@link #fetchTools()} — 连接 MCP Server 获取工具列表</li>
 *   <li>{@link #executeTool(String, String)} — 执行工具调用</li>
 *   <li>{@link #getWriteToolNames()} — 标记哪些工具有副作用需图中断确认</li>
 *   <li>{@link #convertInputSchemaToParamMap(McpToolDefinition)} — 参数 Schema 转换</li>
 * </ul>
 */
public interface McpSource {

    /** 唯一来源标记，如 {@code "MCP:DIDI"}、{@code "MCP:AMAP"} */
    String getSourceName();

    /**
     * 连接 MCP Server 并获取工具列表。
     * <p>
     * 在 {@code @PostConstruct} 时调用，连接失败应抛异常或返回空列表。
     *
     * @return 工具定义列表，不会为 {@code null}
     */
    List<McpToolDefinition> fetchTools();

    /**
     * 执行 MCP 工具调用。
     *
     * @param toolName 工具名
     * @param argsJson 参数字符串（JSON 格式）
     * @return 执行结果的 JSON 字符串
     */
    String executeTool(String toolName, String argsJson);

    /**
     * 本 MCP 源中需要用户确认的 WRITE 工具名集合。
     * <p>
     * 这些工具会被标记为 {@link com.wechatai.common.enums.ToolCategory#WRITE}，
     * 走图中断确认流程。纯 READ 工具不需要确认。
     *
     * @return WRITE 工具名集合，不会为 {@code null}
     */
    Set<String> getWriteToolNames();

    /**
     * 将 MCP 工具定义的 inputSchema 转换为系统参数 Map。
     * <p>
     * 默认实现直接返回 inputSchema 的副本。
     * 不同 MCP 源的参数格式可能不同，子类可覆盖此方法特殊处理。
     */
    default Map<String, Object> convertInputSchemaToParamMap(McpToolDefinition toolDef) {
        return toolDef.getInputSchema() != null
                ? new LinkedHashMap<>(toolDef.getInputSchema())
                : new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of()));
    }
}
