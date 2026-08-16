package com.wechatai.ai.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 桌面 SSE 进度通道：按 sessionId 绑定回调，工具执行过程中推送淡灰状态文案。
 * 未绑定（微信通道 / 非流式）时 emit 为空操作。
 */
@Service
public class DesktopProgressEmitter {

    private final ConcurrentHashMap<String, Consumer<String>> sinks = new ConcurrentHashMap<>();

    public void bind(String sessionId, Consumer<String> sink) {
        if (sessionId == null || sessionId.isBlank() || sink == null) return;
        sinks.put(sessionId, sink);
    }

    public void unbind(String sessionId) {
        if (sessionId == null) return;
        sinks.remove(sessionId);
    }

    public void emit(String sessionId, String text) {
        if (sessionId == null || text == null || text.isBlank()) return;
        Consumer<String> sink = sinks.get(sessionId);
        if (sink == null) return;
        try {
            sink.accept(text.strip());
        } catch (Exception ignored) {
            // 推送失败不影响主流程
        }
    }
}
