package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 邮件发送 Skill — 绑定到 sendEmail 工具。
 * <p>
 * 关键规则：发送前由系统自动拦截并确认，避免误发。
 */
@Component
public class EmailSkill implements Skill {

    @Override
    public String getName() {
        return "email";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("sendEmail");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 邮件发送规则
                - 用户一说完收件人/主题/正文，立即调用 sendEmail
                - 如果用户说的信息不全（缺收件人/正文等），先问用户补充，补充完直接调用
                - 发送失败则告知用户错误原因
                - 不要在正文末尾添加额外签名（如「来自微信智能体助手」），只发用户要求的内容
                """;
    }
}
