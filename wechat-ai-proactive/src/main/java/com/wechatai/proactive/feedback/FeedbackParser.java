package com.wechatai.proactive.feedback;

import com.wechatai.proactive.model.FeedbackSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 用户反馈信号解析器 — 基于正则词库，确定性判断。
 * <p>
 * 优先级：精确匹配（正面/负面） > 隐式信号（轻量规则） > neutral。
 */
public class FeedbackParser {

    private static final Logger log = LoggerFactory.getLogger(FeedbackParser.class);

    // ── 正面信号（强烈） ──
    private static final Set<String> POSITIVE_STRONG = Set.of(
            "谢谢", "太有用了", "帮大忙了", "正是我需要的",
            "好", "厉害", "牛", "完美", "👍");

    // ── 正面信号（温和） ──
    private static final Set<String> POSITIVE_MILD = Set.of(
            "好的", "行", "可以", "ok", "OK", "嗯", "好嘞");

    // ── 负面信号（强烈） ──
    private static final Set<String> NEGATIVE_STRONG = Set.of(
            "不对", "错了", "没用", "你搞错了", "什么鬼",
            "烦", "别推了", "不要了", "闭嘴", "滚");

    // ── 负面信号（温和） ──
    private static final Set<String> NEGATIVE_MILD = Set.of(
            "不用了", "算了", "不需要");

    /**
     * 解析用户回复，返回反馈信号。
     *
     * @param userMessage 用户回复文本（可为 null）
     * @return 反馈信号
     */
    public FeedbackSignal parse(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new FeedbackSignal(FeedbackSignal.Type.NONE, 0.0);
        }

        String trimmed = userMessage.trim();

        // 1. 精确匹配强烈负面（高优先级 —— 敢说出口就说明体验不好）
        for (String kw : NEGATIVE_STRONG) {
            if (trimmed.contains(kw)) {
                log.info("【反馈解析】强负面: \"{}\"", trimmed);
                return new FeedbackSignal(FeedbackSignal.Type.EXPLICIT_NEGATIVE, 1.0);
            }
        }

        // 2. 精确匹配强烈正面
        for (String kw : POSITIVE_STRONG) {
            if (trimmed.contains(kw)) {
                log.info("【反馈解析】强正面: \"{}\"", trimmed);
                return new FeedbackSignal(FeedbackSignal.Type.EXPLICIT_POSITIVE, 1.0);
            }
        }

        // 3. 温和负面
        for (String kw : NEGATIVE_MILD) {
            if (trimmed.contains(kw)) {
                log.info("【反馈解析】温和负面: \"{}\"", trimmed);
                return new FeedbackSignal(FeedbackSignal.Type.EXPLICIT_NEGATIVE, 0.5);
            }
        }

        // 4. 温和正面
        for (String kw : POSITIVE_MILD) {
            if (trimmed.equals(kw) || trimmed.startsWith(kw)) {
                log.info("【反馈解析】温和正面: \"{}\"", trimmed);
                return new FeedbackSignal(FeedbackSignal.Type.IMPLICIT_POSITIVE, 0.3);
            }
        }

        // 5. 有回复但没匹配到 → 中性（可能是追问/新话题）
        log.debug("【反馈解析】中性: \"{}\"", trimmed);
        return new FeedbackSignal(FeedbackSignal.Type.NEUTRAL, 0.0);
    }
}
