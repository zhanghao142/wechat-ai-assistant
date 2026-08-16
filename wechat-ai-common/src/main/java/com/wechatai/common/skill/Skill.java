package com.wechatai.common.skill;

import java.util.List;

/**
 * Skill 接口 — 将行为规则封装为声明式组件。
 * <p>
 * 一个 Skill 绑定到一个或多个工具，当 LLM 调用了绑定的工具后，
 * 下一轮推理时 SkillManager 自动将 {@link #getPromptSegment()} 拼入 system prompt。
 * <p>
 * 用法：
 * <pre>{@code
 * @Component
 * public class WeatherSkill implements Skill {
 *     public String getName() { return "weather"; }
 *     public List<String> getBoundTools() { return List.of("queryWeather"); }
 *     public String getPromptSegment() { return "### 天气回复格式..."; }
 * }
 * }</pre>
 *
 * @see com.wechatai.ai.skill.SkillManager
 */
public interface Skill {

    /** 技能唯一标识 */
    String getName();

    /** 技能绑定的工具名列表（与 @Tool 方法名一致） */
    List<String> getBoundTools();

    /** 拼入 system prompt 的行为规则片段 */
    String getPromptSegment();
}
