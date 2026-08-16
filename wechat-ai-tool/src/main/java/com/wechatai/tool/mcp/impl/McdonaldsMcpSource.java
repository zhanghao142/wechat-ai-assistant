package com.wechatai.tool.mcp.impl;

import com.wechatai.tool.mcp.McpSource;
import com.wechatai.tool.mcp.McpToolDefinition;
import com.wechatai.tool.mcp.config.McdonaldsMcpConfigProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 麦当劳 MCP 源 — 直调麦当劳 MCP Server（JSON-RPC 2.0 over HTTP）。
 * <p>
 * 接入麦当劳中国的优惠券查询/领取、营销日历、会员信息等 5 个工具。
 * 认证方式：Bearer Token（从 https://open.mcd.cn/mcp 生成）。
 * <p>
 * 生命周期由 {@link McpClientManager} 管理。
 * 对应配置前缀 {@code mcp.mcdonalds.*}。
 */
@Component
public class McdonaldsMcpSource implements McpSource {

    private static final Logger log = LoggerFactory.getLogger(McdonaldsMcpSource.class);

    private static final String SOURCE_NAME = "MCP:MCDONALDS";

    /** 需要用户确认的 WRITE 工具 — 下单/兑换/领券/改地址有外部副作用 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "auto-bind-coupons",
            "create-order",
            "mall-create-order",
            "mall-create-order-physical",
            "delivery-create-address"
    );

    private final McdonaldsMcpConfigProperties config;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdGen = new AtomicLong(1);

    private RestClient restClient;
    private boolean connected = false;

    public McdonaldsMcpSource(McdonaldsMcpConfigProperties config, RestClient.Builder restClientBuilder) {
        this.config = config;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<McpToolDefinition> fetchTools() {
        if (!config.isEnabled()) {
            log.info("⏹ 麦当劳 MCP 未启用（mcp.mcdonalds.enabled=false）");
            return List.of();
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("⚠️ 麦当劳 MCP 未启用：mcp.mcdonalds.api-key 为空");
            return List.of();
        }

        if (restClient == null) {
            this.restClient = restClientBuilder.build();
        }

        log.info("🔄 麦当劳 MCP 获取工具列表: {}", config.getBaseUrl());

        try {
            Map<String, Object> request = buildJsonRpcRequest("tools/list",
                    Map.of("_meta", Map.of("progressToken", 1)));

            String responseBody = restClient.post()
                    .uri(config.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.parseMediaType("text/event-stream"))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .body(writeRequest(request))
                    .retrieve()
                    .body(String.class);

            JsonNode root = parseResponse(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                throw new RuntimeException("麦当劳 MCP tools/list 返回错误: " +
                        error.path("message").asText() + " (code=" + error.path("code").asInt() + ")");
            }

            List<McpToolDefinition> tools = new ArrayList<>();
            JsonNode toolsNode = root.path("result").path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    String name = t.path("name").asText();
                    String description = t.path("description").asText("");
                    JsonNode inputSchema = t.path("inputSchema");

                    Map<String, Object> schemaMap = new LinkedHashMap<>();
                    if (!inputSchema.isMissingNode()) {
                        try {
                            schemaMap = objectMapper.convertValue(inputSchema, Map.class);
                        } catch (Exception e) {
                            log.warn("解析工具 {} 的 inputSchema 失败: {}", name, e.getMessage());
                        }
                    }

                    tools.add(new McpToolDefinition(name, description, schemaMap));
                    log.info("  📦 麦当劳 MCP 工具: {} — {}", name, description);
                }
            }

            connected = true;
            log.info("✅ 麦当劳 MCP 已连接，发现 {} 个工具", tools.size());
            return tools;

        } catch (Exception e) {
            connected = false;
            log.warn("⚠️ 麦当劳 MCP 连接失败（mcp.mcdonalds.enabled=false 可关闭）: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String executeTool(String toolName, String argsJson) {
        if (!connected || restClient == null) {
            throw new IllegalStateException("麦当劳 MCP 未连接，请检查配置");
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> arguments = objectMapper.readValue(argsJson, Map.class);
            Map<String, Object> request = buildJsonRpcRequest("tools/call", Map.of(
                    "name", toolName,
                    "arguments", arguments
            ));

            String responseBody = restClient.post()
                    .uri(config.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.parseMediaType("text/event-stream"))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .body(writeRequest(request))
                    .retrieve()
                    .body(String.class);

            JsonNode root = parseResponse(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                String errMsg = error.path("message").asText("未知错误");
                log.warn("【麦当劳 MCP 错误】{}: {}", toolName, errMsg);
                return "{\"error\": \"" + errMsg + "\"}";
            }

            JsonNode content = root.path("result").path("content");
            StringBuilder sb = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode item : content) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.path("text").asText());
                    }
                }
            }

            String result = !sb.isEmpty() ? sb.toString() : "{}";
            log.info("【麦当劳 MCP】{} 完成 ({}ms)", toolName, System.currentTimeMillis() - start);
            return result;

        } catch (Exception e) {
            log.error("【麦当劳 MCP】{} 异常", toolName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public Set<String> getWriteToolNames() {
        return WRITE_TOOLS;
    }

    @PreDestroy
    public void shutdown() {
        connected = false;
        log.info("麦当劳 MCP 已关闭");
    }

    // ========================================================================
    // JSON-RPC 2.0 辅助方法
    // ========================================================================

    private Map<String, Object> buildJsonRpcRequest(String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdGen.getAndIncrement());
        request.put("method", method);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }
        return request;
    }

    private String writeRequest(Map<String, Object> request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 JSON-RPC 请求失败", e);
        }
    }

    private JsonNode parseResponse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析 JSON-RPC 响应失败", e);
        }
    }
}
