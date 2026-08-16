package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 翻译 Skill — 绑定到 translate 工具。
 * <p>
 * LLM 调用 translate 后，下一轮推理自动加载此 Skill，
 * 包含翻译结果格式规范和语言学习场景的处理规则。
 */
@Component
public class TranslateSkill implements Skill {

    @Override
    public String getName() {
        return "translate";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("translate");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 翻译回复格式
                【源语言 → 目标语言】
                原文：xxx
                译文：xxx

                如果用户是在学语言（比如问某个单词怎么读、用法），在译文后附上：
                - 音标/发音提示（如有）
                - 1-2 个简单例句
                - 使用场景说明

                ### 注意事项
                - 用户说"翻译xxx"但没有指定目标语言时，默认翻译为中文
                - 用户说"xxx用英语怎么说"时，目标语言为英语
                - 用户说"xxx是什么意思"时，检测源语言，如果不是中文则翻译为中文并解释
                - 不要对翻译结果做额外评价（如"这个翻译很地道"），直接给出结果即可
                """;
    }
}
