package com.wechatai.tool.mcp;

import com.wechatai.common.enums.ToolCategory;
import com.wechatai.tool.model.vo.ToolDefinitionVO;
import com.wechatai.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具注册桥 — 将所有 {@link McpSource} 提供的工具注册到 {@link ToolRegistry}。
 * <p>
 * 在 {@link ApplicationReadyEvent}（全部就绪后）时触发：
 * <ol>
 *   <li>从 {@link McpClientManager} 获取所有 MCP 源的工具列表</li>
 *   <li>转换为 {@link ToolDefinitionVO} 逐批注册到 ToolRegistry</li>
 * </ol>
 * <p>
 * 新增 MCP 源只需实现 {@link McpSource} 接口并注册为 Bean，本类自动遍历处理。
 */
@Component
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final McpClientManager mcpClientManager;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    /** 来源名 → WRITE 工具名集合（从 McpClientManager 获取） */
    private Set<String> allWriteTools;

    public McpToolBridge(McpClientManager mcpClientManager,
                         ObjectProvider<ToolRegistry> toolRegistryProvider) {
        this.mcpClientManager = mcpClientManager;
        this.toolRegistryProvider = toolRegistryProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerMcpTools() {
        if (!mcpClientManager.isEnabled()) {
            log.info("⏹ MCP 工具注册跳过（无已连接的 MCP 源）");
            return;
        }

        ToolRegistry toolRegistry = toolRegistryProvider.getIfAvailable();
        if (toolRegistry == null) {
            log.warn("⚠️ ToolRegistry 不可用，MCP 工具注册跳过");
            return;
        }

        allWriteTools = mcpClientManager.getAllWriteToolNames();
        int totalCount = 0;

        for (String sourceName : mcpClientManager.getSourceNames()) {
            List<McpToolDefinition> tools = mcpClientManager.getToolsFromSource(sourceName);
            int count = 0;
            for (McpToolDefinition toolDef : tools) {
                ToolDefinitionVO def = convertToDefinition(toolDef, sourceName);
                toolRegistry.registerExternalTool(def, sourceName);
                count++;
                log.info("  📎 {} 工具已注册: {}", sourceName, toolDef.getName());
            }
            totalCount += count;
            log.info("  ✅ {} 注册完成，共 {} 个工具", sourceName, count);
        }

        log.info("✅ MCP 工具注册完成，共 {} 个工具，来源: {}", totalCount, mcpClientManager.getSourceNames());
    }

    private ToolDefinitionVO convertToDefinition(McpToolDefinition toolDef, String sourceName) {
        ToolDefinitionVO def = new ToolDefinitionVO();
        def.setName(toolDef.getName());
        def.setDescription(toolDef.getDescription() != null ? toolDef.getDescription() : "");
        // WRITE 工具走图中断确认
        def.setCategory(allWriteTools.contains(toolDef.getName())
                ? ToolCategory.WRITE : ToolCategory.READ);
        def.setEnabled(true);

        // 转换参数 schema
        Map<String, Object> params = toolDef.getInputSchema() != null
                ? new LinkedHashMap<>(toolDef.getInputSchema())
                : new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of()));
        def.setParameters(params);

        return def;
    }
}
