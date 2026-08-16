package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 简报插图 Skill — 绑定 renderChart。
 */
@Component
public class ChartSkill implements Skill {

    @Override
    public String getName() {
        return "chart";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("renderChart");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 简报插图规则（renderChart）
                - 何时画：报告中有 ≥2 个对象的可比数值（对比/趋势/占比），且数字来自检索材料
                - 何时不画：纯定性、单一数字、口径明显不可比、数据不足
                - 怎么选类型：多对象同一指标 → bar；多时期同一指标 → line；占比构成且类目≤6 → pie
                - 一张简报通常 1～2 张图；先 renderChart，再把返回的 `![...](url)` **整行原样**写入 generateDocument
                - 禁止手搓 QuickChart URL；禁止为出图编造数值；画不出就用文字表格
                """;
    }
}
