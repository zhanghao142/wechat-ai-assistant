package com.wechatai.tool.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP 客户端管理器 — 管理所有 {@link McpSource} 实例。
 * <p>
 * 自动收集 Spring 容器中所有 {@link McpSource} Bean，建立工具名 → 来源的索引，
 * 提供统一的工具查找和执行入口。
 * <p>
 * 新增一个 MCP 源只需新建一个类实现 {@link McpSource} 并用 {@code @Component} 注册，
 * 其余代码零改动。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final List<McpSource> sources;

    /** 全局工具名 → McpSource 映射（所有 MCP 源的并集） */
    private final Map<String, McpSource> toolIndex = new LinkedHashMap<>();

    /** 来源名 → 来源映射 */
    private final Map<String, McpSource> sourceIndex = new LinkedHashMap<>();

    public McpClientManager(List<McpSource> sources) {
        this.sources = sources;
    }

    @PostConstruct
    public void init() {
        for (McpSource source : sources) {
            String name = source.getSourceName();
            sourceIndex.put(name, source);

            // 从 source 获取工具列表建立索引
            List<McpToolDefinition> tools = source.fetchTools();
            if (tools == null || tools.isEmpty()) {
                log.warn("⏹ MCP 源 {} 返回空工具列表", name);
                continue;
            }
            for (McpToolDefinition tool : tools) {
                if (toolIndex.containsKey(tool.getName())) {
                    log.warn("⚠️ 工具名冲突 {} 已在 {} 中注册, 跳过 {}", tool.getName(),
                            toolIndex.get(tool.getName()).getSourceName(), name);
                    continue;
                }
                toolIndex.put(tool.getName(), source);
            }
            log.info("✅ MCP 源已注册: {} — {} 个工具", name, tools.size());
        }

        if (sourceIndex.isEmpty()) {
            log.info("⏹ 未发现任何 MCP 源");
        } else {
            log.info("✅ MCP 管理器就绪，共 {} 个源，{} 个工具: {}",
                    sourceIndex.size(), toolIndex.size(), toolIndex.keySet());
        }
    }

    // ── 对外接口 ──

    /** 是否有至少一个 MCP 源已连接 */
    public boolean isEnabled() {
        return !sourceIndex.isEmpty() && !toolIndex.isEmpty();
    }

    /** 获取所有来源标记 */
    public Set<String> getSourceNames() {
        return sourceIndex.keySet();
    }

    /** 检查指定工具是否注册自任何 MCP 源 */
    public boolean hasTool(String toolName) {
        return toolIndex.containsKey(toolName);
    }

    /** 获取指定工具的来源标记 */
    public String getSourceForTool(String toolName) {
        McpSource source = toolIndex.get(toolName);
        return source != null ? source.getSourceName() : null;
    }

    /** 获取指定工具的 WRITE 工具名集合（所有 MCP 源的并集） */
    public Set<String> getAllWriteToolNames() {
        Set<String> all = new LinkedHashSet<>();
        for (McpSource source : sources) {
            all.addAll(source.getWriteToolNames());
        }
        return all;
    }

    /**
     * 执行 MCP 工具调用。
     * <p>
     * 根据工具名自动路由到对应的 {@link McpSource} 执行。
     *
     * @param toolName 工具名
     * @param argsJson 参数字符串（JSON 格式）
     * @return 执行结果的 JSON 字符串
     * @throws IllegalArgumentException 工具不存在或来源未连接
     */
    public String executeTool(String toolName, String argsJson) {
        McpSource source = toolIndex.get(toolName);
        if (source == null) {
            throw new IllegalArgumentException("未知的 MCP 工具: " + toolName);
        }
        return source.executeTool(toolName, argsJson);
    }

    /** 获取指定 MCP 源的工具定义列表 */
    public List<McpToolDefinition> getToolsFromSource(String sourceName) {
        McpSource source = sourceIndex.get(sourceName);
        if (source == null) return List.of();
        return source.fetchTools();
    }

    /** 获取所有 MCP 源的工具定义列表（合并） */
    public List<McpToolDefinition> getAllTools() {
        List<McpToolDefinition> all = new ArrayList<>();
        for (McpSource source : sources) {
            List<McpToolDefinition> tools = source.fetchTools();
            if (tools != null) all.addAll(tools);
        }
        return all;
    }
}
