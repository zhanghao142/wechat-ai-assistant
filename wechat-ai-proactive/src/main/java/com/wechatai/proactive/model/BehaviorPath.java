package com.wechatai.proactive.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 行为路径实体 — 对应 behavior_paths 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorPath {

    private Long id;
    private String pathId;
    private String userId;

    /** 意图分类 */
    private String triggerIntent;
    /** 一句话描述触发场景 */
    private String triggerSummary;
    /** 触发文本的向量（JSON数组） */
    private String triggerEmbedding;

    /** 工具调用链 [{tool, paramsSchema, reason}] */
    private String toolChain;

    /** 结果 */
    private String outcome;   // success / partial / failure / ignored / harmful

    /** 用户反馈文本 */
    private String userFeedback;
    /** 反馈类型 */
    private String feedbackType;

    /** Beta分布参数 */
    private int alpha;
    private int beta;
    private int useCount;
    private int successCount;
    private int failCount;

    /** Critic 生成的规则 JSON 数组 ["规则1", "规则2"] */
    private String criticRules;

    /** 最后使用时间 */
    private Instant lastUsed;
    private Instant createdAt;
    private Instant updatedAt;

    // ── 非持久化字段（检索时填充） ──

    /** 检索综合得分 */
    private transient double retrievalScore;
    /** 向量相似度 */
    private transient float similarity;

    /** 获取当前置信度 */
    public double getConfidence() {
        return (double) alpha / (alpha + beta);
    }
}
