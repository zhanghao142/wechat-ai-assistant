package com.wechatai.ai.skill;

import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.common.skill.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Skill 管理器 — 根据上一轮 LLM 调用的工具，动态将绑定的 Skill 拼入 system prompt。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>llm_think 第 1 轮：无历史工具调用 → 不加 Skill</li>
 *   <li>LLM 返回 tool_calls → 记入 state.toolExecutionRequests</li>
 *   <li>tool_execute 执行工具</li>
 *   <li>llm_think 第 2 轮：SkillManager 读到上轮调用了 queryWeather → 加载 WeatherSkill</li>
 *   <li>invoke() 结束 → state 销毁 → Skill 自动卸载</li>
 * </ol>
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Map<String, Skill> skillMap = new LinkedHashMap<>();

    public SkillManager(List<Skill> skills) {
        if (skills != null) {
            for (Skill s : skills) {
                if (skillMap.put(s.getName(), s) != null) {
                    log.warn("Skill 名冲突: {} 被覆盖", s.getName());
                }
            }
        }
        log.info("✅ SkillManager 初始化完成，共 {} 个 Skill: {}", skillMap.size(), skillMap.keySet());
    }

    /**
     * 根据 state 中上一轮的 toolExecutionRequests，将绑定的 Skill prompt 拼到 basePrompt 末尾。
     *
     * @param state      当前图状态
     * @param basePrompt 原始的 system prompt
     * @return 拼接后的完整 prompt（无绑定 Skill 时返回原 prompt）
     */
    @SuppressWarnings("unchecked")
    public String enrich(ChatGraphState state, String basePrompt) {
        List<Object> toolRequests = state.getToolExecutionRequests();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return basePrompt;
        }

        // 取上一轮调过的工具名
        Set<String> calledTools = new HashSet<>();
        for (Object req : toolRequests) {
            if (req instanceof Map) {
                Map<String, Object> tc = (Map<String, Object>) req;
                Object funcObj = tc.get("function");
                if (funcObj instanceof Map) {
                    Object name = ((Map<String, Object>) funcObj).get("name");
                    if (name instanceof String) {
                        calledTools.add((String) name);
                    }
                }
            }
        }

        if (calledTools.isEmpty()) {
            return basePrompt;
        }

        // 查找命中的 Skill
        Set<String> loaded = new LinkedHashSet<>();
        List<Skill> matched = new ArrayList<>();
        for (Skill skill : skillMap.values()) {
            for (String toolName : skill.getBoundTools()) {
                if (calledTools.contains(toolName) && loaded.add(skill.getName())) {
                    matched.add(skill);
                    break;
                }
            }
        }

        if (matched.isEmpty()) {
            return basePrompt;
        }

        // 拼接到 base prompt 末尾
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n");
        for (Skill s : matched) {
            sb.append(s.getPromptSegment()).append("\n\n");
        }

        String result = sb.toString().stripTrailing();
        log.info("【SkillManager】加载 Skill: {} → 基于工具调用: {}", loaded, calledTools);
        return result;
    }
}
