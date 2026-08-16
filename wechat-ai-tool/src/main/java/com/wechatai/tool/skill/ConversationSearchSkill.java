package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史对话搜索 Skill — 绑定到 searchConversation 工具。
 * <p>
 * LLM 调用 searchConversation 后，下一轮推理自动加载此 Skill，
 * 包含历史对话搜索的使用规则。提示词与源 SYSTEM_PROMPT 完全一致。
 */
@Component
public class ConversationSearchSkill implements Skill {

    @Override
    public String getName() {
        return "conversation-search";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("searchConversation");
    }

    @Override
    public String getPromptSegment() {
        return """
                ## 历史对话搜索规则
                - 当用户问"我叫什么"、"我之前说了什么"、"刚才那个xxx"等涉及之前聊天内容的问题时，
                  并且当前上下文记不清相关内容
                → **必须先调 searchConversation** 搜索历史记录，不要凭当前上下文猜测
                - 如果你不确定答案，先调 searchConversation 搜一下，确实找不到再说不知道
                - 关键词尽量用用户提到的具体名词（文件名、话题名、人名等）
                """;
    }
}
