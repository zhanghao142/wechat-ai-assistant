package com.wechatai.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块服务 — 将解析后的纯文本切分为固定大小的 chunk。
 * <p>
 * 切分策略（按优先级）：
 * <ol>
 *   <li>优先在 {@code \n\n}（段落边界）处切</li>
 *   <li>其次在 {@code \n}（换行）处切</li>
 *   <li>最后在句末标点（。！？）处切</li>
 * </ol>
 * 相邻 chunk 有重叠，保证跨 chunk 的语义连续性。
 */
@Component
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final int maxChars;
    private final int overlapChars;

    public ChunkService(@Value("${wechat.rag.chunk.max-chars:500}") int maxChars,
                        @Value("${wechat.rag.chunk.overlap-chars:50}") int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    /**
     * 将文本切分为 chunks。
     *
     * @param content 文档解析后的纯文本
     * @return 切分后的文本块列表，内容过短时返回单元素列表
     */
    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (content.length() <= maxChars) {
            return List.of(content.strip());
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(start + maxChars, content.length());

            // 最后一段，直接取完并退出
            if (end >= content.length()) {
                String chunk = content.substring(start).strip();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                break;
            }

            // 尝试在自然边界处切断
            end = findBestSplitPoint(content, start, end);

            String chunk = content.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 下一段起始位置 = 当前结束 - 重叠量
            start = end - overlapChars;
            if (start >= content.length()) break;
        }

        log.debug("【ChunkService】切分完成: {} 个 chunk, 原文 {} 字符", chunks.size(), content.length());
        return chunks;
    }

    /**
     * 在 [start, end) 范围内找到最佳切分点。
     * 优先段落边界 → 换行 → 句末标点 → 原 end。
     */
    private int findBestSplitPoint(String content, int start, int end) {
        // 向后搜索范围：从 end 往前最多找 1/3 maxChars
        int searchStart = Math.max(start + maxChars * 2 / 3, end - maxChars / 3);

        // ① 找段落边界
        int split = content.lastIndexOf("\n\n", end);
        if (split > searchStart) return split + 2;

        // ② 找换行
        split = content.lastIndexOf('\n', end);
        if (split > searchStart) return split + 1;

        // ③ 找句末标点
        split = lastIndexOfAny(content, end, "。！？\n", searchStart);
        if (split > searchStart) return split + 1;

        // ④ 兜底：原 end 位置
        return end;
    }

    /**
     * 在 [searchStart, end) 范围内查找任意指定字符的最后出现位置。
     */
    private int lastIndexOfAny(String content, int end, String chars, int searchStart) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int pos = content.lastIndexOf(chars.charAt(i), end - 1);
            if (pos > best && pos >= searchStart) {
                best = pos;
            }
        }
        return best;
    }
}
