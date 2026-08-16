package com.wechatai.proactive.critic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则注入器 — 将 Critic 生成的规则回注到 Agent system prompt。
 * <p>
 * 与 {@code SkillManager} 模式一致：LLM 在下一次推理时自动加载相关规则。
 * 规则按 scope 分为全局（global，所有用户生效）和用户特定（user_specific）。
 * <p>
 * 存储：内存 ConcurrentHashMap（重启丢失，后续可持久化到 MySQL）。
 */
public class RuleInjector {

    private static final Logger log = LoggerFactory.getLogger(RuleInjector.class);

    /** 全局规则 — ruleId → 规则文本 */
    private final Map<String, String> globalRules = new ConcurrentHashMap<>();

    /** 用户特定规则 — userId → (ruleId → 规则文本) */
    private final Map<String, Map<String, String>> userRules = new ConcurrentHashMap<>();

    /** 最大规则数量（防膨胀） */
    private static final int MAX_GLOBAL_RULES = 50;
    private static final int MAX_USER_RULES = 20;

    /**
     * 存储一条规则。
     *
     * @param ruleId  规则唯一标识
     * @param rule    规则文本
     * @param scope   global / user_specific
     * @param userId  仅 user_specific 时使用
     */
    public void store(String ruleId, String rule, String scope, String userId) {
        if (rule == null || rule.isBlank()) return;

        if ("global".equals(scope)) {
            if (globalRules.size() >= MAX_GLOBAL_RULES) return;
            globalRules.put(ruleId, rule);
            log.info("【规则注入】全局规则已存储: ruleId={}", ruleId);
        } else if (userId != null) {
            Map<String, String> ur = userRules.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
            if (ur.size() >= MAX_USER_RULES) return;
            ur.put(ruleId, rule);
            log.info("【规则注入】用户规则已存储: userId={} ruleId={}", userId, ruleId);
        }
    }

    /**
     * 获取应注入到 system prompt 的规则片段。
     *
     * @param userId 当前用户（null 则只返回全局规则）
     * @return 规则文本块，无规则时返回空字符串
     */
    public String getRulesForPrompt(String userId) {
        StringBuilder sb = new StringBuilder();

        if (!globalRules.isEmpty()) {
            sb.append("\n\n## 经验规则（全局）\n");
            for (String rule : globalRules.values()) {
                sb.append("- ").append(rule).append("\n");
            }
        }

        if (userId != null && userRules.containsKey(userId)) {
            Map<String, String> ur = userRules.get(userId);
            if (!ur.isEmpty()) {
                sb.append("\n## 经验规则（个人偏好）\n");
                for (String rule : ur.values()) {
                    sb.append("- ").append(rule).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /** 获取当前规则数量 */
    public int getGlobalRuleCount() { return globalRules.size(); }
    public int getUserRuleCount(String userId) {
        Map<String, String> ur = userRules.get(userId);
        return ur != null ? ur.size() : 0;
    }
}
