package com.wechatai.tool.impl;

import com.wechatai.tool.config.BrowserProperties;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器会话管理器：为每个 sessionId 维护独立的浏览器上下文（隔离 cookie / localStorage）。
 * <p>
 * 负责：创建/查找/销毁浏览器实例、空闲回收、并发控制、URL 安全校验。
 */
@Component
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private final BrowserProperties props;
    private final Playwright playwright;                       // 全局单例
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-cleanup");
        t.setDaemon(true);
        return t;
    });

    public BrowserSessionManager(BrowserProperties props) {
        this.props = props;
        this.playwright = Playwright.create();
        log.info("Playwright 全局实例已创建");

        // 启动空闲回收定时器
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupIdleSessions, 60, 60, TimeUnit.SECONDS);
        log.info("浏览器空闲回收定时器已启动 (间隔 60s, 空闲阈值 {}s)", props.getIdleTimeoutSeconds());
    }

    // ==================== 公开 API ====================

    /**
     * 获取或创建指定会话的 Page
     */
    public Page getPage(String sessionId) {
        BrowserSession session = sessions.computeIfAbsent(sessionId, this::createSession);
        session.lastAccessTime = System.currentTimeMillis();
        return session.page;
    }

    /**
     * 获取指定会话的 BrowserContext（供 iframe 遍历等高级操作）。
     *
     * @return BrowserContext，或 null（会话不存在时）
     */
    public BrowserContext getContext(String sessionId) {
        BrowserSession session = sessions.get(sessionId);
        if (session != null) {
            session.lastAccessTime = System.currentTimeMillis();
            return session.context;
        }
        return null;
    }

    /**
     * 检查 URL 是否在黑名单/内网范围内
     */
    public void checkUrlAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("无效URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            throw new SecurityException("仅允许 http/https 协议，当前: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL 缺少主机名: " + url);
        }

        // 黑名单匹配
        String blacklist = props.getUrlBlacklist();
        if (blacklist != null && !blacklist.isBlank()) {
            for (String pattern : blacklist.split(",")) {
                if (matchGlob(pattern.trim(), url)) {
                    throw new SecurityException("URL 命中黑名单: " + pattern.trim());
                }
            }
        }

        // DNS 解析后校验内网 IP
        try {
            InetAddress addr = InetAddress.getByName(host);
            byte[] octets = addr.getAddress();
            if (octets.length == 4) {
                int first = octets[0] & 0xFF;
                int second = octets[1] & 0xFF;
                if (first == 127) throw new SecurityException("禁止访问回环地址: " + host);
                if (first == 10) throw new SecurityException("禁止访问内网地址: " + host);
                if (first == 172 && second >= 16 && second <= 31) throw new SecurityException("禁止访问内网地址: " + host);
                if (first == 192 && second == 168) throw new SecurityException("禁止访问内网地址: " + host);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DNS 解析失败: {} - {}", host, e.getMessage());
            // DNS 解析失败可能是内网域名，保守拒绝
            throw new SecurityException("无法解析域名，可能是内网地址: " + host);
        }
    }

    /**
     * 销毁指定会话的浏览器
     */
    public void destroySession(String sessionId) {
        BrowserSession session = sessions.remove(sessionId);
        if (session != null) {
            safeClose(session);
            log.info("会话 {} 浏览器已销毁", sessionId);
        }
    }

    // ==================== 内部方法 ====================

    private BrowserSession createSession(String sessionId) {
        // 实例数上限检查
        if (sessions.size() >= props.getMaxInstances()) {
            throw new IllegalStateException("浏览器实例已达上限(" + props.getMaxInstances() + ")，请稍后重试");
        }

        BrowserType.LaunchOptions launchOpts = new BrowserType.LaunchOptions()
                .setHeadless(props.isHeadless())
                .setChannel("msedge");  // 使用系统 Edge，支持 H.264/AAC 等专有编解码器

        // 仅当显式配置了路径且文件存在时才指定（覆盖 channel 自动查找）
        String configuredPath = props.getExecutablePath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            java.nio.file.Path exePath = java.nio.file.Paths.get(configuredPath);
            if (java.nio.file.Files.exists(exePath)) {
                launchOpts.setExecutablePath(exePath);
                log.info("使用指定浏览器: {}", configuredPath);
            } else {
                log.warn("配置的浏览器路径不存在: {}，回退到 Playwright 自动查找", configuredPath);
            }
        }

        Browser browser = playwright.chromium().launch(launchOpts);
        BrowserContext context = browser.newContext();
        Page page = context.newPage();
        page.setDefaultTimeout(props.getTimeout());

        log.info("会话 {} 浏览器已创建 (headless={}, 实例数 {}/{})",
                sessionId, props.isHeadless(), sessions.size() + 1, props.getMaxInstances());

        return new BrowserSession(playwright, browser, context, page);
    }

    private void cleanupIdleSessions() {
        long now = System.currentTimeMillis();
        long idleThreshold = props.getIdleTimeoutSeconds() * 1000L;

        sessions.forEach((sessionId, session) -> {
            if (now - session.lastAccessTime > idleThreshold) {
                log.info("会话 {} 空闲超时，销毁浏览器", sessionId);
                sessions.remove(sessionId);
                safeClose(session);
            }
        });
    }

    private void safeClose(BrowserSession session) {
        try { session.context.close(); } catch (Exception ignored) {}
        try { session.browser.close(); } catch (Exception ignored) {}
    }

    /**
     * 简单 glob 匹配：支持 * 通配符
     */
    private boolean matchGlob(String pattern, String input) {
        if (pattern.equals("*")) return true;
        // 将 glob 转为简单正则
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
        return input.matches("(?i).*" + regex + ".*");
    }

    // ==================== 内部类 ====================

    private static class BrowserSession {
        final Playwright playwright;
        final Browser browser;
        final BrowserContext context;
        final Page page;
        volatile long lastAccessTime;

        BrowserSession(Playwright playwright, Browser browser, BrowserContext context, Page page) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
            this.page = page;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
}
