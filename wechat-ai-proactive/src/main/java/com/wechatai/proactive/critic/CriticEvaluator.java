package com.wechatai.proactive.critic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.agent.LlmCaller;
import com.wechatai.proactive.model.DecisionTrace;
import com.wechatai.proactive.model.FeedbackSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Critic LLM 评价器 — 调用 DeepSeek 分析失败原因并生成规则。
 * <p>
 * 约束（设计文档 §8.3）：
 * <ol>
 *   <li>不访问当前会话历史记忆</li>
 *   <li>只看（事件, Agent动作, 用户反应）三元组</li>
 *   <li>输出严格 JSON</li>
 *   <li>Critic 不参与对话</li>
 *   <li>同一 trace 最多调用一次</li>
 * </ol>
 */
@Service
public class CriticEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CriticEvaluator.class);

    private static final String CRITIC_PROMPT = """
            你是独立决策评估器。你不参与对话，不维护用户关系。
            你唯一的职责是分析一次 Agent 操作的结果，给出客观评价。

            ## 场景
            用户意图: {intent}
            触发事件: {event_summary}

            ## Agent做了什么
            工具调用链: {tool_chain}
            最终回复: {agent_response}

            ## 用户反应
            用户回复: "{user_reaction}"
            反应类型: {reaction_type}

            ## 请分析（输出严格 JSON，不要任何其他文字）
            {
              "score": 0-10,
              "verdict": "good|acceptable|poor|harmful",

              "what_went_right": "如果评价>=6分，哪里做得好（一句话，可为空字符串）",
              "what_went_wrong": "如果评价<6分，哪里出了问题（一句话，可为空字符串）",

              "root_cause": "timing|topic_irrelevant|overreach|redundancy|tool_error|tone|context_miss|other",

              "is_path_issue": true/false,
              "path_fix_suggestion": "如果是路径本身的问题，怎么改进工具链（可为空字符串）",

              "learned_rule": "可以提炼出的通用规则（可为空字符串）",
              "rule_scope": "global|user_specific"
            }""";

    private final LlmCaller llmCaller;
    private final ObjectMapper objectMapper;

    public CriticEvaluator(LlmCaller llmCaller) {
        this.llmCaller = llmCaller;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行 Critic 分析。
     *
     * @param trace       决策追踪（含事件、工具链、Agent 回复）
     * @param feedback    用户反馈信号
     * @param userMessage 用户原话
     * @return Critic 分析结果；失败返回 null
     */
    public CriticVerdict evaluate(DecisionTrace trace, FeedbackSignal feedback, String userMessage) {
        if (trace == null) return null;

        try {
            String prompt = CRITIC_PROMPT
                    .replace("{intent}", trace.getEventType() != null ? trace.getEventType() : "unknown")
                    .replace("{event_summary}", extractSummary(trace.getEventPayload()))
                    .replace("{tool_chain}", trace.getExecutedToolChain() != null ? trace.getExecutedToolChain() : "无")
                    .replace("{agent_response}", trace.getAgentResponse() != null ? truncate(trace.getAgentResponse(), 500) : "无")
                    .replace("{user_reaction}", userMessage != null ? userMessage : "")
                    .replace("{reaction_type}", feedback.getType().name());

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", "你只输出 JSON，不要解释。"),
                    Map.of("role", "user", "content", prompt)
            );

            Map<String, Object> result = llmCaller.callLlm(messages, List.of());
            String content = (String) result.get("content");

            if (content == null || content.isBlank()) {
                log.warn("【Critic】LLM 返回空内容");
                return null;
            }

            // 提取 JSON（LLM 可能用 ``` 包裹）
            String json = extractJson(content);
            CriticVerdict verdict = objectMapper.readValue(json, CriticVerdict.class);

            log.info("【Critic】分析完成 score={} verdict={} root_cause={} hasRule={}",
                    verdict.getScore(), verdict.getVerdict(),
                    verdict.getRootCause(), verdict.hasRule());

            return verdict;

        } catch (Exception e) {
            log.error("【Critic】分析失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractSummary(String eventPayload) {
        if (eventPayload == null) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(eventPayload, Map.class);
            Object msg = payload.get("message");
            return msg != null ? msg.toString() : eventPayload;
        } catch (Exception e) {
            return eventPayload.length() > 200 ? eventPayload.substring(0, 200) : eventPayload;
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
