package com.wechatai.tool.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 滴滴出行 MCP 客户端配置。
 * <p>
 * 对应 application.properties 中 {@code mcp.didi.*} 前缀的配置项。
 * 通过 {@link Component} 自动注册为 Bean，由 Spring 绑定属性。
 *
 * <pre>{@code
 * # 启用 MCP（默认关闭）
 * mcp.didi.enabled=true
 * # API Key（从滴滴 MCP 控制台获取 https://mcp.didichuxing.com/）
 * mcp.didi.api-key=your_api_key_here
 * # 调试用沙箱环境（默认 true，正式切换为 false）
 * mcp.didi.sandbox=true
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "mcp.didi")
public class McpConfigProperties {

    /** 是否启用 MCP 客户端 */
    private boolean enabled = false;

    /** 滴滴 MCP API Key（从 https://mcp.didichuxing.com/ 获取） */
    private String apiKey = "";

    /** 正式环境 URL */
    private String baseUrl = "https://mcp.didichuxing.com/mcp-servers";

    /** 沙箱调试环境 URL */
    private String sandboxUrl = "https://mcp.didichuxing.com/mcp-servers-sandbox";

    /** 是否使用沙箱环境（默认 true，调试阶段用） */
    private boolean sandbox = true;

    /** 连接超时（秒） */
    private int timeoutSeconds = 30;

    // ── getters & setters ──

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getSandboxUrl() { return sandboxUrl; }
    public void setSandboxUrl(String sandboxUrl) { this.sandboxUrl = sandboxUrl; }

    public boolean isSandbox() { return sandbox; }
    public void setSandbox(boolean sandbox) { this.sandbox = sandbox; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
