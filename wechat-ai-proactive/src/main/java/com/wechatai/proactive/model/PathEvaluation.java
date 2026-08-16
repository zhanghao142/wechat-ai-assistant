package com.wechatai.proactive.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 路径评价实体 — 对应 path_evaluations 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathEvaluation {

    private Long id;
    private String pathId;
    private String traceId;

    /** 用户反应原文 */
    private String userReaction;
    /** 用户反应类型 */
    private String userReactionType;

    /** 是否触发了 Critic */
    private boolean criticTriggered;
    private Integer criticScore;
    private String criticVerdict;
    private String criticRootCause;
    private String criticRuleGenerated;

    /** 环境快照 */
    private String contextSnapshot;

    private Instant createdAt;
}
