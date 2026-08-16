package com.wechatai.ai.node;

import com.wechatai.ai.state.ChatGraphState;
import org.bsc.langgraph4j.action.AsyncNodeAction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 解析入参写入状态 — 对应 demo 的 {@code UserInputNode}。
 */
public class PrepareInputNode implements AsyncNodeAction<ChatGraphState> {

    @Override
    public CompletableFuture<Map<String, Object>> apply(ChatGraphState state) {
        // 入参已在 invoke 初始 Map 中，此节点预留扩展（归一化、校验等）
        return CompletableFuture.completedFuture(Map.of());
    }
}
