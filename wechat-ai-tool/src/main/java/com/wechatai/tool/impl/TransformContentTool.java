package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内容风格转换工具（WRITE）— 将文本按目标格式进行结构化变换。
 *
 * <p>支持的目标风格：
 * <ul>
 *   <li>{@code wechat} — 微信公众号推文</li>
 *   <li>{@code email} — 正式邮件</li>
 *   <li>{@code minutes} — 会议纪要</li>
 *   <li>{@code xiaohongshu} — 小红书笔记</li>
 *   <li>{@code speech} — 演讲稿</li>
 * </ul>
 *
 * <p>创新点：不依赖 LLM 即可完成结构级变换（分段、模板套用、标记注入），
 * LLM 只需在此基础上做语义润色，大幅降低 token 消耗。
 */
@Component
public class TransformContentTool {

    private static final Logger log = LoggerFactory.getLogger(TransformContentTool.class);

    /** 可用的目标风格 */
    private static final Set<String> AVAILABLE_STYLES = Set.of(
            "wechat", "email", "minutes", "xiaohongshu", "speech"
    );

    /** 用于按句号/感叹号/问号拆分句子的模式 */
    private static final Pattern SENTENCE_SPLITTER = Pattern.compile("(?<=[。！？!?])");

    /** 中文字数阈值：超过此值认为原文较长，启用摘要式处理 */
    private static final int LONG_CONTENT_THRESHOLD = 2000;

    // ==================== @Tool 方法 ====================

    @Tool("将文本内容转换为指定的风格格式。支持：wechat(公众号推文)、email(正式邮件)、minutes(会议纪要)、xiaohongshu(小红书笔记)、speech(演讲稿)")
    public TransformResult transformContent(
            @P("待转换的原始文本内容") String content,
            @P("目标风格，可选值: wechat, email, minutes, xiaohongshu, speech") String targetStyle) {

        String style = (targetStyle != null) ? targetStyle.strip().toLowerCase() : "";

        if (content == null || content.isBlank()) {
            return new TransformResult(false, "错误：待转换内容为空", style, null);
        }

        if (!AVAILABLE_STYLES.contains(style)) {
            return new TransformResult(false,
                    "不支持的目标风格: " + style + "。可选值: " + String.join(", ", AVAILABLE_STYLES),
                    style, null);
        }

        long start = System.currentTimeMillis();

        try {
            String transformed = switch (style) {
                case "wechat" -> toWechatArticle(content);
                case "email" -> toFormalEmail(content);
                case "minutes" -> toMeetingMinutes(content);
                case "xiaohongshu" -> toXiaohongshu(content);
                case "speech" -> toSpeech(content);
                default -> throw new IllegalStateException("Unexpected style: " + style);
            };

            long elapsed = System.currentTimeMillis() - start;
            log.info("内容转换完成: style={}, 原文{}字, 转换耗时{}ms", style, content.length(), elapsed);

            return new TransformResult(true, "转换成功", style, transformed);

        } catch (Exception e) {
            log.error("内容转换失败: style={}", style, e);
            return new TransformResult(false, "转换失败: " + e.getMessage(), style, null);
        }
    }

    // ==================== 各风格转换逻辑 ====================

    /**
     * → 微信公众号推文
     * <p>特征：短段落（≤3句）、emoji点缀、摘要导语、"点击关注"引导
     */
    private String toWechatArticle(String text) {
        StringBuilder sb = new StringBuilder();

        // 导语
        String lead = extractLead(text, 80);
        sb.append("▎导语\n");
        sb.append("　　").append(lead).append("\n\n");

        // 正文：拆分段落，每段用短句重排
        sb.append("▎正文\n");
        List<String> paragraphs = splitParagraphs(text);
        int paraIdx = 1;
        for (String para : paragraphs) {
            // 每个段落拆成短句组（3句一组）
            List<String> shortGroups = toShortSentenceGroups(para, 3);
            for (String group : shortGroups) {
                sb.append(formatWechatParagraph(group, paraIdx));
                paraIdx++;
            }
        }

        // 互动引导
        sb.append("\n— END —\n\n");
        sb.append("📌 如果觉得有用，欢迎 **点赞 + 在看** 支持一下～\n");
        sb.append("📌 点击下方卡片关注，获取更多干货内容 →\n");

        return sb.toString();
    }

    /**
     * → 正式邮件
     * <p>特征：主题行 + 称呼 + 正文分段 + 落款 + 日期
     */
    private String toFormalEmail(String text) {
        StringBuilder sb = new StringBuilder();

        // 主题行 — 取首句或前30字作为默认主题
        String subject = extractLead(text, 60);
        sb.append("【邮件主题】").append(subject).append("\n\n");

        // 称呼
        sb.append("尊敬的[收件人姓名/团队]：\n\n");
        sb.append("　　您好！\n\n");

        // 正文
        List<String> paragraphs = splitParagraphs(text);
        for (String para : paragraphs) {
            sb.append("　　").append(para.trim()).append("\n\n");
        }

        // 落款
        sb.append("　　此致\n");
        sb.append("敬礼\n\n");
        sb.append("[发件人姓名]\n");
        sb.append("[部门/职位]\n");
        sb.append(new java.text.SimpleDateFormat("yyyy年MM月dd日").format(new java.util.Date())).append("\n");

        return sb.toString();
    }

    /**
     * → 会议纪要
     * <p>特征：结构化字段（主题/时间/参会人/讨论要点/决议/待办）
     */
    private String toMeetingMinutes(String text) {
        StringBuilder sb = new StringBuilder();

        sb.append("═══════════════════════════════════\n");
        sb.append("          会  议  纪  要\n");
        sb.append("═══════════════════════════════════\n\n");

        sb.append("📋 会议主题：").append(extractLead(text, 50)).append("\n");
        sb.append("📅 会议时间：[请补充具体时间]\n");
        sb.append("📍 会议地点：[请补充具体地点]\n");
        sb.append("👥 参会人员：[请补充参会人员]\n");
        sb.append("📝 记录人：[请补充记录人]\n\n");

        sb.append("───────────────────────────────────\n");
        sb.append("一、讨论要点\n");
        sb.append("───────────────────────────────────\n");
        // 按段落拆分，每段作为一个议题
        List<String> paragraphs = splitParagraphs(text);
        for (int i = 0; i < paragraphs.size(); i++) {
            sb.append("\n　议题").append(i + 1).append("：").append(paragraphs.get(i).trim()).append("\n");
            sb.append("　　• 要点：[待提炼]\n");
            sb.append("　　• 讨论：[待补充]\n");
        }

        sb.append("\n───────────────────────────────────\n");
        sb.append("二、决议事项\n");
        sb.append("───────────────────────────────────\n");
        sb.append("　1. [请补充具体决议]\n");
        sb.append("　2. [请补充具体决议]\n");

        sb.append("\n───────────────────────────────────\n");
        sb.append("三、待办事项\n");
        sb.append("───────────────────────────────────\n");
        sb.append("　□ 待办1 — 负责人：[待指定] — 截止：[待定]\n");
        sb.append("　□ 待办2 — 负责人：[待指定] — 截止：[待定]\n");
        sb.append("　□ 待办3 — 负责人：[待指定] — 截止：[待定]\n");

        sb.append("\n═══════════════════════════════════\n");
        sb.append("以上内容由 AI 辅助生成，请核对后确认。\n");

        return sb.toString();
    }

    /**
     * → 小红书笔记
     * <p>特征：标题党 + 短句分行 + emoji密集 + 话题标签 + 互动引导
     */
    private String toXiaohongshu(String text) {
        StringBuilder sb = new StringBuilder();

        // 标题（带emoji前缀）
        String lead = extractLead(text, 40);
        sb.append("✨ ").append(lead).append("\n\n");

        // 正文：每句一行，穿插emoji
        String[] sentences = SENTENCE_SPLITTER.split(text);
        String[] emojis = {"💡", "🔥", "📌", "💯", "🎯", "✨", "🌟", "💪", "✅", "📢"};

        int emojiIdx = 0;
        int charCount = 0;
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > 50) {
                // 长句拆短
                trimmed = String.join("\n", toShortLines(trimmed, 25));
            }
            sb.append(emojis[emojiIdx % emojis.length]).append(" ").append(trimmed).append("\n\n");
            emojiIdx++;
            charCount += trimmed.length();
            if (charCount > 1000) break; // 小红书笔记不宜过长
        }

        // 话题标签
        sb.append("———\n");
        sb.append("#干货分享 #实用技巧 #效率提升 #学习方法\n");
        sb.append("#内容创作 #知识分享 #成长日记\n\n");

        // 互动引导
        sb.append("💬 你有什么想法？评论区告诉我吧～\n");
        sb.append("❤️ 觉得有用就双击点个赞！\n");
        sb.append("⭐ 收藏起来慢慢看～\n");
        sb.append("👉 关注我，获取更多精彩内容\n");

        return sb.toString();
    }

    /**
     * → 演讲稿
     * <p>特征：开场白 + 主体（分点阐述）+ 过渡句 + 总结升华 + 结束语
     */
    private String toSpeech(String text) {
        StringBuilder sb = new StringBuilder();

        sb.append("════════════════════════════════\n");
        sb.append("      演  讲  稿\n");
        sb.append("════════════════════════════════\n\n");

        // 开场白
        sb.append("【开场白】\n");
        sb.append("尊敬的各位领导、各位来宾，大家好！\n\n");
        sb.append("　　今天很荣幸能站在这里，与各位分享一个我深思已久的话题——")
                .append(extractLead(text, 30)).append("。\n\n");

        // 主体
        sb.append("【主体内容】\n");
        List<String> paragraphs = splitParagraphs(text);
        String[] transitions = {
                "首先，", "其次，", "再者，", "此外，", "最后，",
                "值得注意的是，", "更重要的是，", "综上所述，"
        };

        for (int i = 0; i < paragraphs.size() && i < transitions.length; i++) {
            sb.append("\n").append(transitions[i]).append("\n\n");
            sb.append("　　").append(paragraphs.get(i).trim()).append("\n");
        }

        // 过渡（倒数第二段后）
        sb.append("\n【过渡】\n");
        sb.append("　　说到这里，我想请大家思考一个问题：")
                .append(extractLead(text, 30)).append("？这个问题的答案，也许就在我们每个人的心中。\n\n");

        // 总结升华
        sb.append("【总结升华】\n");
        sb.append("　　回顾今天的分享，我想用一句话来概括：\n");
        sb.append("　　行动胜于空谈，坚持成就未来。\n");
        sb.append("　　让我们携手并进，共同创造更美好的明天！\n\n");

        // 结束语
        sb.append("【结束语】\n");
        sb.append("　　我的分享到此结束，感谢各位的聆听！\n");
        sb.append("　　如果有任何问题，欢迎随时与我交流。\n\n");
        sb.append("════════════════════════════════\n");
        sb.append("谢谢大家！（鞠躬）\n");

        return sb.toString();
    }

    // ==================== 通用文本处理工具方法 ====================

    /**
     * 提取导语：取原文前 n 个有效字符作为摘要/标题
     */
    private String extractLead(String text, int maxLen) {
        String cleaned = text.replaceAll("\\s+", "").trim();
        if (cleaned.length() <= maxLen) return cleaned;
        // 尝试在句号处截断
        int cut = cleaned.substring(0, maxLen).lastIndexOf("。");
        if (cut > maxLen / 2) return cleaned.substring(0, cut + 1);
        cut = cleaned.substring(0, maxLen).lastIndexOf("，");
        if (cut > maxLen / 2) return cleaned.substring(0, cut);
        return cleaned.substring(0, maxLen) + "…";
    }

    /**
     * 按换行和空行拆分段落
     */
    private List<String> splitParagraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("\n{2,}|\\n(?=\\S{10,})"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 将段落拆成短句组（每组最多 maxSentences 句）
     */
    private List<String> toShortSentenceGroups(String paragraph, int maxSentences) {
        String[] sentences = SENTENCE_SPLITTER.split(paragraph);
        List<String> groups = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int count = 0;

        for (String s : sentences) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            buf.append(trimmed);
            count++;
            if (count >= maxSentences) {
                groups.add(buf.toString());
                buf.setLength(0);
                count = 0;
            }
        }
        if (!buf.isEmpty()) groups.add(buf.toString());
        return groups.isEmpty() ? List.of(paragraph) : groups;
    }

    /**
     * 将长句拆成短行（按逗号/分号拆分，每行最多 maxChars 字）
     */
    private List<String> toShortLines(String sentence, int maxChars) {
        List<String> lines = new ArrayList<>();
        String[] parts = sentence.split("(?<=[，,；;])");
        StringBuilder buf = new StringBuilder();
        for (String part : parts) {
            if (buf.length() + part.length() > maxChars && !buf.isEmpty()) {
                lines.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(part);
        }
        if (!buf.isEmpty()) lines.add(buf.toString());
        return lines.isEmpty() ? List.of(sentence) : lines;
    }

    /**
     * 格式化微信推文段落（缩进 + 序号标记）
     */
    private String formatWechatParagraph(String text, int idx) {
        String[] emojis = {"📌", "💡", "🔍", "✨", "📊", "🎯", "💭", "⚡"};
        String prefix = emojis[(idx - 1) % emojis.length];
        // 短段落直接换行
        return prefix + " " + text.trim() + "\n\n";
    }

    // ==================== 返回结构 ====================

    /**
     * 转换结果
     */
    public record TransformResult(
            boolean success,
            String message,
            String targetStyle,
            String transformedContent
    ) {
        /**
         * 供 LLM 阅读的格式化输出
         */
        @Override
        public String toString() {
            if (!success) return "❌ " + message;
            return "✅ 已完成 [" + targetStyle + "] 风格转换\n\n"
                    + "━━━━━━ 转换结果 ━━━━━━\n\n"
                    + transformedContent + "\n\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "💡 提示：以上为结构化转换结果，请根据需要进一步调整语气和措辞。";
        }
    }
}
