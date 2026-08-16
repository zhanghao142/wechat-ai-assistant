package com.wechatai.proactive.critic;

import com.wechatai.proactive.model.DecisionTrace;
import com.wechatai.proactive.model.FeedbackSignal;

/**
 * Critic 触发判定器 — 决定是否对一次 Agent 操作启动 Critic 分析。
 * <p>
 * 规则（来自设计文档 §8.1）：
 * <ul>
 *   <li>failure / harmful → 必须分析</li>
 *   <li>ignored（用户不理）→ 必须分析</li>
 *   <li>partial（部分成功）→ 必须分析</li>
 *   <li>success + 冷启动新建了路径 → 分析一次（总结成功模式）</li>
 *   <li>其他 success → 不触发</li>
 * </ul>
 */
public class CriticTriggerDecider {

    /**
     * 判断是否应触发 Critic 分析。
     *
     * @param trace    决策追踪记录
     * @param feedback 用户反馈信号
     * @return true = 应触发 Critic
     */
    public boolean shouldTrigger(DecisionTrace trace, FeedbackSignal feedback) {
        if (trace == null) return false;

        String outcome = trace.getOutcome();

        // 失败/有害 → 必须分析
        if ("failure".equals(outcome) || "harmful".equals(outcome)) {
            return true;
        }

        // 用户不理 → 分析为什么
        if ("ignored".equals(outcome)) {
            return true;
        }

        // 部分成功 → 分析哪里不足
        if ("partial".equals(outcome)) {
            return true;
        }

        // 成功 + 冷启动新建了路径 → 分析一次，总结成功模式
        if ("success".equals(outcome) && trace.isNewPathCreated()) {
            return true;
        }

        return false;
    }
}
