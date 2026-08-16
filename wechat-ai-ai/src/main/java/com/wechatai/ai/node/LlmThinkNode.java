package com.wechatai.ai.node;

import com.wechatai.ai.ai.LlmService;
import com.wechatai.ai.state.ChatGraphState;
import org.bsc.langgraph4j.action.AsyncNodeAction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LLM 推理节点 — 生成回复或标记需要调用工具。
 */
public class LlmThinkNode implements AsyncNodeAction<ChatGraphState> {

    private final LlmService llmService;

    public LlmThinkNode(LlmService llmService) {
        this.llmService = llmService;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(ChatGraphState state) {
        Map<String, Object> update = llmService.think(state);
        return CompletableFuture.completedFuture(update);
    }
}
