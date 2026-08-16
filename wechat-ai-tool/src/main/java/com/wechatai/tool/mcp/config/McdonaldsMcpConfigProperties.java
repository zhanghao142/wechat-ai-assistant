package com.wechatai.tool.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 麦当劳 MCP 客户端配置。
 * <p>
 * 对应 application.properties 中 {@code mcp.mcdonalds.*} 前缀的配置项。
 * Token 在麦当劳开放平台生成：https://open.mcd.cn/mcp。
 *
 * <pre>{@code
 * # 启用麦当劳 MCP（默认关闭）
 * mcp.mcdonalds.enabled=true
 * # API Token（从 https://open.mcd.cn/mcp 生成）
 * mcp.mcdonalds.api-key=
 * # MCP Server 地址
 * mcp.mcdonalds.base-url=https://mcp.mcd.cn/mcp-servers/mcd-mcp
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "mcp.mcdonalds")
public class McdonaldsMcpConfigProperties {

    /** 是否启用麦当劳 MCP */
    private boolean enabled = false;

    /** 麦当劳 MCP Token（从 https://open.mcd.cn/mcp 生成） */
    private String apiKey = "";

    /** MCP Server 基础 URL */
    private String baseUrl = "https://mcp.mcd.cn/mcp-servers/mcd-mcp";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
