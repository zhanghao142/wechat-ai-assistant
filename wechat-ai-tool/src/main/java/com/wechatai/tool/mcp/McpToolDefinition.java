package com.wechatai.tool.mcp;

import java.util.Map;

/**
 * MCP 工具定义（轻量 POJO，不依赖任何外部 SDK）。
 * <p>
 * 由每个 {@link McpSource} 在 {@code fetchTools()} 时返回，
 * 包含工具名、描述、参数 Schema（JSON Schema 格式）。
 */
public class McpToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
}
