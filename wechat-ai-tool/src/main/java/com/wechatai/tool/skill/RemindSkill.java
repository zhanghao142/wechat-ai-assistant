package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提醒 Skill — 绑定到 setRemind 工具。
 * <p>
 * LLM 调用 setRemind 后，下一轮推理自动加载此 Skill，
 * 指导 LLM 如何正确处理用户的时间表达。
 */
@Component
public class RemindSkill implements Skill {

    @Override
    public String getName() {
        return "remind";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("setRemind");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 定时提醒时间处理规则
                设置提醒时，如果用户说的是以下类型的时间，直接把用户原话传给 remindAt 参数，不要自己换算成绝对时间：
                - 相对时间：X分钟后、X小时后、X天后、X秒后
                - 模糊时间：半小时后、一炷香后
                - 自然时间：明天、后天、下周、下周一、今晚、今晚八点、明天早上

                只有当用户明确说了具体日期时间（如「2026年7月23日下午3点」），才自己转换成 yyyy-MM-dd HH:mm 格式。

                ### 设置提醒时的行为限制（严格遵循）
                设置提醒时**只调用 setRemind 一个工具**，禁止同时调用其他工具。

                用户说的所有需求（查天气、搜信息、发邮件等），全部放到 setRemind 的 content 参数中。
                提醒触发时系统会自动处理这些需求，你不需要现在提前做。

                示例：
                ❌ 用户说「一分钟后提醒我开会并查杭州天气」→ 调 setRemind + queryWeather（错误）
                ✅ 用户说「一分钟后提醒我开会并查杭州天气」→ 只调 setRemind，content="开会并查杭州天气"（正确）
                """;
    }
}
