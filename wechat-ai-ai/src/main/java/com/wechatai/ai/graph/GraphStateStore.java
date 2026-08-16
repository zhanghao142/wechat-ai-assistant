package com.wechatai.ai.graph;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挂起状态存储 — 记录哪些用户的图被中断，等待用户确认。
 * <p>
 * key = userId（微信用户 ID），value = 中断时的上下文。
 * 每 60 秒扫描一次，清理超过 10 分钟的过期挂起。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>图中断 → {@link #suspend(String, SuspendedContext)} → 存进去</li>
 *   <li>用户确认 → {@link #remove(String)} → 取出来恢复执行</li>
 *   <li>超时 → {@link #cleanupExpired()} → 自动清理</li>
 * </ol>
 */
@Component
public class GraphStateStore {

    private static final Logger log = LoggerFactory.getLogger(GraphStateStore.class);

    /** 挂起 TTL：10 分钟（与设计文档一致） */
    private static final long EXPIRATION_MS = 10 * 60 * 1000L;

    private final Map<String, SuspendedContext> store = new ConcurrentHashMap<>();

    /**
     * 挂起用户的图。
     */
    public void suspend(String userId, SuspendedContext ctx) {
        store.put(userId, ctx);
        log.info("【挂起】userId={}, toolName={}, threadId={}", userId, ctx.getToolName(), ctx.getThreadId());
    }

    /**
     * 获取用户的挂起上下文。
     */
    public SuspendedContext getSuspended(String userId) {
        return store.get(userId);
    }

    /**
     * 移除用户的挂起状态（确认 / 取消 / 超时）。
     */
    public void remove(String userId) {
        SuspendedContext removed = store.remove(userId);
        if (removed != null) {
            log.info("【解除挂起】userId={}, toolName={}", userId, removed.getToolName());
        }
    }

    /**
     * 定时清理过期挂起 — 每 60 秒执行一次。
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpired() {
        long deadline = System.currentTimeMillis() - EXPIRATION_MS;
        store.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getSuspendedAt() < deadline;
            if (expired) {
                log.warn("【挂起超时】userId={}, toolName={}，已自动清理",
                        entry.getKey(), entry.getValue().getToolName());
            }
            return expired;
        });
    }

    /**
     * 当前挂起数量（监控/日志用）。
     */
    public int size() {
        return store.size();
    }

    /**
     * 挂起上下文 — 图中断时保存的完整信息。
     */
    @Data
    @AllArgsConstructor
    public static class SuspendedContext {
        /** 微信用户 ID（同时也是图调用的 threadId） */
        private String userId;
        /** 会话 ID */
        private String sessionId;
        /** LangGraph threadId（恢复图执行时用） */
        private String threadId;
        /** 被中断的工具名（如 sendEmail） */
        private String toolName;
        /** 工具参数 JSON（用于拼确认消息） */
        private String toolArgs;
        /** 挂起时间戳（毫秒） */
        private long suspendedAt;
    }
}
