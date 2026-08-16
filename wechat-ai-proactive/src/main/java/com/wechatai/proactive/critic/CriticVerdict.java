package com.wechatai.proactive.critic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Critic 分析输出结构（对应设计文档 §8.2 JSON 格式）。
 * <p>
 * LLM 输出 snake_case JSON，通过 @JsonProperty 映射到 Java 驼峰字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CriticVerdict {

    /** 0-10 评分 */
    private int score;

    /** good / acceptable / poor / harmful */
    private String verdict;

    /** 如果评分 >= 6，哪里做得好 */
    @JsonProperty("what_went_right")
    private String whatWentRight;

    /** 如果评分 < 6，哪里出了问题 */
    @JsonProperty("what_went_wrong")
    private String whatWentWrong;

    /** timing / topic_irrelevant / overreach / redundancy / tool_error / tone / context_miss / other */
    @JsonProperty("root_cause")
    private String rootCause;

    /** 是否是路径本身的问题 */
    @JsonProperty("is_path_issue")
    private boolean pathIssue;

    /** 如果是路径问题，改进建议 */
    @JsonProperty("path_fix_suggestion")
    private String pathFixSuggestion;

    /** 可提炼的通用规则 */
    @JsonProperty("learned_rule")
    private String learnedRule;

    /** global / user_specific */
    @JsonProperty("rule_scope")
    private String ruleScope;

    // ── 便捷判断 ──

    public boolean isGood() {
        return score >= 7;
    }

    public boolean isPoor() {
        return score < 4;
    }

    public boolean hasRule() {
        return learnedRule != null && !learnedRule.isBlank();
    }
}
