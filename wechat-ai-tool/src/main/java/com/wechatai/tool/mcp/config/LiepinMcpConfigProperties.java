package com.wechatai.tool.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 猎聘 MCP 客户端配置。
 * <p>
 * 对应 application.properties 中 {@code mcp.liepin.*} 前缀的配置项。
 * 凭证在猎聘官网生成：登录 liepin.com → 打开 liepin.com/mcp/server → 生成凭证。
 *
 * <pre>{@code
 * # 启用猎聘 MCP（默认关闭）
 * mcp.liepin.enabled=true
 * # 凭证（从猎聘 MCP 控制台生成）
 * mcp.liepin.credential=
 * # MCP Server 地址
 * mcp.liepin.base-url=https://www.liepin.com/mcp/server
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "mcp.liepin")
public class LiepinMcpConfigProperties {

    /** 是否启用猎聘 MCP */
    private boolean enabled = false;

    /** 猎聘 MCP 凭证（从 https://www.liepin.com/mcp/server 生成） */
    private String credential = "";

    /** MCP Server 基础 URL */
    private String baseUrl = "https://open-agent.liepin.com/mcp/user";

    /** 连接超时（秒） */
    private int timeoutSeconds = 30;

    // ── getters & setters ──

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
