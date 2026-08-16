package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档生成 Skill — 绑定到 generateDocument 工具。
 * <p>
 * LLM 调用 generateDocument 后，下一轮推理自动加载此 Skill，
 * 包含生成文档的内容规范、回复格式等规则。
 */
@Component
public class DocumentGenSkill implements Skill {

    @Override
    public String getName() {
        return "documentGen";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("generateDocument", "renderChart");
    }

    @Override
    public String getPromptSegment() {
        return """
                ### 文档生成规则
                - 研究报告/简报/总结成文件：优先用 generateDocument，不要改用 write_file
                - 调用 generateDocument 之前，AI 自己先生成完整、通顺的文档内容
                - 内容要结构清晰：标题、分节、段落；简报优先用 .md
                - 文档内容用中文，如果用户要求其他语言则跟随用户要求
                - 【插图】正文含 ≥2 个可比数值时：先调用 renderChart，把返回的 `![标题](url)` 整行插入 md 对应小节；无可靠数据则不插图
                - 保存后回复用户「已保存，需要查收」之类的话
                - 不要在最终回复中重复展示文档全文（用户已收到文件），简要概括即可
                """;
    }
}
