package com.wechatai.common.util;

import java.util.UUID;

/**
 * 链路追踪 ID 工具。
 * <p>
 * 基于 ThreadLocal 在单次请求内传递 traceId，写入统一响应与日志，
 * 便于跨模块排查。请求结束时应 {@link #clear()} 防止线程池复用污染。
 */
public final class TraceIdUtil {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdUtil() {}

    public static String get() {
        return HOLDER.get();
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    /** 获取当前 traceId，不存在则生成新的 UUID（去横线） */
    public static String getOrCreate() {
        String id = HOLDER.get();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "");
            HOLDER.set(id);
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
