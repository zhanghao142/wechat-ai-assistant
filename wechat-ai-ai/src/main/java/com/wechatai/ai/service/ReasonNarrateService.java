package com.wechatai.ai.service;

import com.wechatai.ai.ai.LlmService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 桌面思维链叙述：写成连贯段落，支持流式输出。
 */
@Service
@RequiredArgsConstructor
public class ReasonNarrateService {

    private static final Logger log = LoggerFactory.getLogger(ReasonNarrateService.class);

    private static final String SYSTEM = """
            你是助手的「工作叙述」写手。侧栏会把你的输出排成连贯散文，不要写成标签口号。

            用第一人称中文写一小段（1～2 句，约 40～70 字），要同时做到：
            1) 逻辑：相对上一段推进了什么结论或取舍
            2) 工作量：点一下做过什么（检索/精读/比对），别空喊「材料够了」
            3) 前瞻：下一步怎么走（一笔带过即可）

            硬性禁止：
            - 不要标题、列表、Markdown、引号包整段
            - 不要「独白/查证/判断」这类标签腔
            - 不要同义反复「上文」里已经说过的意思
            - 不要干巴巴复读「搜到N条 / 网页到手了 / 继续搜」
            - 不要编造具体评分数值、价格、条数；线索里没有的数字不要写
            - 不要套话（「好的」「让我来」「作为AI」）
            - 不要写成小作文，超过 80 字就太长

            文风：像向同事口述一句进展——短、有因果。
            """;

    private final LlmService llmService;

    public String narrate(String userText, String phase, String eventText,
                          List<String> recentLines, String factsHint) {
        return narrateStream(userText, phase, eventText, recentLines, factsHint, null);
    }

    /**
     * 流式叙述：onDelta 收到每个 token 增量；返回清洗后的全文。
     */
    public String narrateStream(String userText, String phase, String eventText,
                                List<String> recentLines, String factsHint,
                                Consumer<String> onDelta) {
        List<Map<String, Object>> messages = buildMessages(
                userText, phase, eventText, recentLines, factsHint);
        try {
            String raw;
            if (onDelta != null) {
                raw = llmService.callLlmStream(messages, onDelta);
            } else {
                Map<String, Object> result = llmService.callLlm(messages, List.of());
                raw = result.get("content") != null ? String.valueOf(result.get("content")) : "";
            }
            String cleaned = clean(raw);
            if (cleaned.isBlank()) {
                return fallback(eventText, phase, factsHint);
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("【思维链独白】生成失败: {}", e.getMessage());
            return fallback(eventText, phase, factsHint);
        }
    }

    private static List<Map<String, Object>> buildMessages(String userText, String phase,
                                                           String eventText,
                                                           List<String> recentLines,
                                                           String factsHint) {
        StringBuilder user = new StringBuilder();
        user.append("用户目标：").append(nullToEmpty(userText)).append('\n');
        user.append("当前阶段：").append(nullToEmpty(phase)).append('\n');
        user.append("事件与线索：").append(nullToEmpty(eventText)).append('\n');
        if (factsHint != null && !factsHint.isBlank()) {
            user.append("工作量与事实：").append(factsHint.trim()).append('\n');
        }
        if (recentLines != null && !recentLines.isEmpty()) {
            user.append("上文（请接续推进，勿复读）：\n");
            for (String line : recentLines) {
                if (line == null || line.isBlank() || "…".equals(line)) continue;
                user.append("¶ ").append(line.trim()).append('\n');
            }
        }
        user.append("请写下一段工作叙述：");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM));
        messages.add(Map.of("role", "user", "content", user.toString()));
        return messages;
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim()
                .replaceAll("(?s)^```.*?\\n", "")
                .replaceAll("```$", "")
                .replaceAll("^[\"「『]+|[\"」』]+$", "")
                .replaceAll("\\s*\n+\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        s = s.replaceFirst("^(好的[，,]?|让我来|我会)+", "");
        s = s.replaceFirst("^(独白|查证|判断)[：:\\s]+", "");
        if (s.length() > 200) {
            s = s.substring(0, 200).replaceAll("[，,。.!？?；;：:]+$", "") + "…";
        }
        return s.trim();
    }

    private static String fallback(String eventText, String phase, String factsHint) {
        String work = nullToEmpty(factsHint).trim();
        String p = nullToEmpty(phase);
        if ("start".equals(p)) {
            return "先钉死目标和约束，再决定先查什么。";
        }
        if ("search_done".equals(p)) {
            return work.isEmpty()
                    ? "检索告一段落，按维度筛完就转入精读。"
                    : "检索已铺开（" + clip(work, 28) + "），挑可信来源精读。";
        }
        if ("read_done".equals(p)) {
            return "精读钉住关键论据，对照目标看缺口再决定是否补搜。";
        }
        if ("write".equals(p) || "confirm".equals(p)) {
            return "材料够交付了，下一步落文档或等你确认。";
        }
        if ("error".equals(p)) {
            return "这条路不通，换来源或改策略。";
        }
        if ("finish".equals(p) || "reply".equals(p)) {
            return work.isEmpty()
                    ? "本轮收束，结论可直接用。"
                    : "做完了（" + clip(work, 28) + "），按目标收束。";
        }
        String e = nullToEmpty(eventText).trim();
        if (!e.isEmpty() && e.length() <= 60) return e;
        return "按任务逻辑推进到下一判断点。";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
