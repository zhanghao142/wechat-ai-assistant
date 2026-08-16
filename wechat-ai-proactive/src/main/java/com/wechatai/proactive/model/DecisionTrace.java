package com.wechatai.proactive.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 决策追踪实体 — 对应 decision_traces 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTrace {

    private String traceId;
    private String userId;
    private String sessionId;

    /** 事件类型 */
    private String eventType;
    /** 事件负载 */
    private String eventPayload;
    /** 事件 embedding */
    private String eventEmbedding;

    /** 匹配到的路径列表 JSON */
    private String matchedPaths;
    /** 选中策略: fast_path / slow_path / hybrid */
    private String selectedStrategy;

    /** 实际执行的工具链 */
    private String executedToolChain;
    private int llmCalls;
    private int totalTokens;
    private int latencyMs;

    /** Agent 最终回复 */
    private String agentResponse;
    /** 用户反应 */
    private String userReaction;
    /** 结果 */
    private String outcome;
    /** 是否新建了路径 */
    private boolean newPathCreated;
    private String newPathId;

    private Instant createdAt;
}
