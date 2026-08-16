package com.wechatai.tool.mcp.impl;

import com.wechatai.tool.mcp.McpSource;
import com.wechatai.tool.mcp.McpToolDefinition;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.*;

/**
 * Filesystem MCP 源 — 通过 stdio 启动 Node.js 子进程，接入文件系统 MCP Server。
 * <p>
 * 使用官方 MCP Java SDK 的 {@link StdioClientTransport} 连接
 * {@code @modelcontextprotocol/server-filesystem}，
 * 暴露读写指定目录下文件的能力。无需 API Key。
 */
@Slf4j
@Component
public class FilesystemMcpSource implements McpSource {

    @Value("${mcp.filesystem.enabled:false}")
    private boolean enabled;

    @Value("${mcp.filesystem.allowed-dir:C:/mcp-sandbox}")
    private String allowedDir;

    @Value("${mcp.filesystem.npx-path:C:/Program Files/nodejs/npx.cmd}")
    private String npxPath;

    private McpSyncClient mcpClient;

    @Override
    public String getSourceName() {
        return "MCP:FILESYSTEM";
    }

    @Override
    @PostConstruct
    public List<McpToolDefinition> fetchTools() {
        if (!enabled) {
            log.info("Filesystem MCP 未启用（mcp.filesystem.enabled=false）");
            return Collections.emptyList();
        }

        try {
            connectClient();
            McpSchema.ListToolsResult result = mcpClient.listTools();
            List<McpToolDefinition> defs = new ArrayList<>();
            for (McpSchema.Tool t : result.tools()) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", t.inputSchema().properties());
                defs.add(new McpToolDefinition(t.name(), t.description(), schema));
                log.info("  📦 Filesystem MCP 工具: {}", t.name());
            }
            log.info("✅ Filesystem MCP 已连接，发现 {} 个工具（目录: {}）", defs.size(), allowedDir);
            return defs;
        } catch (Exception e) {
            log.error("Filesystem MCP 连接失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String executeTool(String toolName, String argsJson) {
        if (!enabled || mcpClient == null) {
            return "{\"error\": \"Filesystem MCP 未启用\"}";
        }
        try {
            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(toolName, parseArgs(argsJson)));
            return result.content() != null ? result.content().toString() : "{}";
        } catch (Exception e) {
            log.error("Filesystem MCP 执行失败: {}", toolName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public Set<String> getWriteToolNames() {
        // Filesystem MCP 的写入工具有副作用
        return Set.of("write_file", "create_directory", "move_file",
                "delete_files", "edit_file");
    }

    private String resolvedAllowedDir() {
        return Paths.get(allowedDir).toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private void connectClient() {
        String dir = resolvedAllowedDir();
        log.info("Filesystem MCP 启动，允许目录: {}", dir);
        ServerParameters params = ServerParameters.builder(npxPath)
                .args("-y", "@modelcontextprotocol/server-filesystem", dir)
                .build();
        StdioClientTransport transport = new StdioClientTransport(params);
        mcpClient = McpClient.sync(transport).build();
        mcpClient.initialize();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(argsJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @PreDestroy
    public void close() {
        if (mcpClient != null) {
            try { mcpClient.closeGracefully(); } catch (Exception ignored) {}
            log.info("Filesystem MCP 已关闭");
        }
    }
}
