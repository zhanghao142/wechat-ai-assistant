package com.wechatai.tool.skill;

import com.wechatai.common.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网页搜索 Skill — 绑定到 readUrl 和 searchInternet 工具。
 * <p>
 * LLM 调用网页相关工具后，下一轮推理自动加载此 Skill，
 * 包含网页搜索与链接处理规则。提示词与源 SYSTEM_PROMPT 完全一致。
 */
@Component
public class WebSearchSkill implements Skill {

    @Override
    public String getName() {
        return "web-search";
    }

    @Override
    public List<String> getBoundTools() {
        return List.of("readUrl", "searchInternet");
    }

    @Override
    public String getPromptSegment() {
        return """
                ## 网页搜索与链接处理规则
                - 用户发链接 → 调 readUrl 读取内容，然后回复用户
                - 用户问不熟悉的话题、问新闻、问最新动态、研究报告 → 先调 searchInternet（一次会合并多搜索引擎）
                - 关键词准确简洁，尽量中文
                - 【少搜多读】同一研究任务通常 searchInternet 1～2 次即可；结果里已有 ≥3 条可用「链接」时，优先 readUrl 精读 2～3 个，禁止同义词反复空搜
                - 只有结果明显跑题/全是垃圾站时，才换关键词再搜一次
                - 【强制】readUrl / 浏览器 navigate 的 URL 必须逐字复制自 searchInternet 返回的「链接」字段
                - 【禁止】编造、猜测、拼接、改写任何网址（含 gov.cn / weather.com.cn / 百科路径）
                - 搜索结果里没有合适链接时：如实说明缺来源，或换关键词再搜；不要自己发明 URL
                - 整理结论时优先用工具读到的正文；不要向用户罗列一长串链接
                """;
    }
}
