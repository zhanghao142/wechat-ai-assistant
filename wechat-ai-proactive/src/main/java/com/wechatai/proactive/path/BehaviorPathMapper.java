package com.wechatai.proactive.path;

import com.wechatai.proactive.model.BehaviorPath;
import com.wechatai.proactive.model.DecisionTrace;
import com.wechatai.proactive.model.PathEvaluation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 行为路径 MyBatis Mapper — 操作 behavior_paths / decision_traces / path_evaluations 三表。
 */
@Mapper
public interface BehaviorPathMapper {

    // ── behavior_paths ──

    @Insert("""
            INSERT INTO behavior_paths (path_id, user_id, trigger_intent, trigger_summary,
                trigger_embedding, tool_chain, outcome, user_feedback, feedback_type,
                alpha, beta, use_count, success_count, fail_count, last_used,
                critic_rules, created_at, updated_at)
            VALUES (#{pathId}, #{userId}, #{triggerIntent}, #{triggerSummary},
                #{triggerEmbedding}, #{toolChain}, #{outcome}, #{userFeedback}, #{feedbackType},
                #{alpha}, #{beta}, #{useCount}, #{successCount}, #{failCount},
                #{lastUsed}, #{criticRules}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BehaviorPath path);

    @Select("SELECT * FROM behavior_paths WHERE path_id = #{pathId}")
    BehaviorPath findByPathId(String pathId);

    @Select("""
            SELECT * FROM behavior_paths
            WHERE trigger_intent = #{triggerIntent}
            ORDER BY (alpha / (alpha + beta)) DESC
            LIMIT #{limit}
            """)
    List<BehaviorPath> findByIntent(String triggerIntent, int limit);

    @Update("""
            UPDATE behavior_paths
            SET alpha = #{alpha}, beta = #{beta}, use_count = #{useCount},
                success_count = #{successCount}, fail_count = #{failCount},
                outcome = #{outcome}, user_feedback = #{userFeedback},
                feedback_type = #{feedbackType}, last_used = #{lastUsed}, updated_at = NOW()
            WHERE path_id = #{pathId}
            """)
    int updateConfidence(BehaviorPath path);

    /** 标记路径为 archived（不再参与检索） */
    @Update("UPDATE behavior_paths SET outcome = 'ignored', updated_at = NOW() WHERE path_id = #{pathId}")
    int archive(String pathId);

    /** 获取所有活跃路径（合并扫描用） */
    @Select("SELECT * FROM behavior_paths WHERE outcome NOT IN ('ignored','harmful','degraded')")
    List<BehaviorPath> findAllActive();

    /** 合并路径统计：将来源的 α/β/use/success 累加到目标路径 */
    @Update("""
            UPDATE behavior_paths
            SET alpha = alpha + #{alpha}, beta = beta + #{beta},
                use_count = use_count + #{useCount},
                success_count = success_count + #{successCount},
                fail_count = fail_count + #{failCount},
                updated_at = NOW()
            WHERE path_id = #{targetPathId}
            """)
    int mergeInto(String targetPathId, int alpha, int beta, int useCount, int successCount, int failCount);

    /** 查找超过保质期的低热度路径（归档用） */
    @Select("""
            SELECT * FROM behavior_paths
            WHERE outcome NOT IN ('ignored','harmful')
              AND last_used < DATE_SUB(NOW(), INTERVAL 90 DAY)
              AND use_count < 10
            """)
    List<BehaviorPath> findExpired();

    /** 查找矛盾路径（好坏各半 → 标记 degraded） */
    @Select("""
            SELECT * FROM behavior_paths
            WHERE outcome = 'success'
              AND fail_count >= 3 AND success_count >= 3
            """)
    List<BehaviorPath> findConflicting();

    /** 标记为 degraded */
    @Update("UPDATE behavior_paths SET outcome = 'degraded', updated_at = NOW() WHERE path_id = #{pathId}")
    int markDegraded(String pathId);

    /** 统计某用户对某路径的使用情况（个人权重用） */
    @Select("""
            SELECT COUNT(*) FROM decision_traces
            WHERE (new_path_id = #{pathId} OR
                   JSON_CONTAINS(matched_paths, JSON_OBJECT('pathId', #{pathId})))
              AND user_id = #{userId}
            """)
    int countUserUsage(String pathId, String userId);

    @Select("""
            SELECT COUNT(*) FROM decision_traces
            WHERE (new_path_id = #{pathId} OR
                   JSON_CONTAINS(matched_paths, JSON_OBJECT('pathId', #{pathId})))
              AND user_id = #{userId}
              AND outcome = 'success'
            """)
    int countUserSuccess(String pathId, String userId);

    // ── decision_traces ──

    @Insert("""
            INSERT INTO decision_traces (trace_id, user_id, session_id, event_type, event_payload,
                event_embedding, matched_paths, selected_strategy, executed_tool_chain,
                llm_calls, total_tokens, latency_ms, agent_response, user_reaction,
                outcome, new_path_created, new_path_id, created_at)
            VALUES (#{traceId}, #{userId}, #{sessionId}, #{eventType}, #{eventPayload},
                #{eventEmbedding}, #{matchedPaths}, #{selectedStrategy}, #{executedToolChain},
                #{llmCalls}, #{totalTokens}, #{latencyMs}, #{agentResponse}, #{userReaction},
                #{outcome}, #{newPathCreated}, #{newPathId}, NOW())
            """)
    int insertTrace(DecisionTrace trace);

    @Select("SELECT * FROM decision_traces WHERE trace_id = #{traceId}")
    DecisionTrace findByTraceId(String traceId);

    @Select("""
            SELECT * FROM decision_traces
            WHERE user_id = #{userId} AND outcome = 'pending_feedback'
            ORDER BY created_at DESC LIMIT 1
            """)
    DecisionTrace findLatestPendingFeedback(String userId);

    @Update("""
            UPDATE decision_traces
            SET user_reaction = #{userReaction}, outcome = #{outcome}, llm_calls = #{llmCalls}
            WHERE trace_id = #{traceId}
            """)
    int updateTraceFeedback(DecisionTrace trace);

    @Update("""
            UPDATE decision_traces
            SET executed_tool_chain = #{executedToolChain}, agent_response = #{agentResponse},
                llm_calls = #{llmCalls}, latency_ms = #{latencyMs},
                outcome = #{outcome}, new_path_created = #{newPathCreated},
                new_path_id = #{newPathId}
            WHERE trace_id = #{traceId}
            """)
    int updateTraceAfterAgent(DecisionTrace trace);

    // ── path_evaluations ──

    @Insert("""
            INSERT INTO path_evaluations (path_id, trace_id, user_reaction, user_reaction_type,
                critic_triggered, critic_score, critic_verdict, critic_root_cause,
                critic_rule_generated, context_snapshot, created_at)
            VALUES (#{pathId}, #{traceId}, #{userReaction}, #{userReactionType},
                #{criticTriggered}, #{criticScore}, #{criticVerdict}, #{criticRootCause},
                #{criticRuleGenerated}, #{contextSnapshot}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvaluation(PathEvaluation evaluation);
}
