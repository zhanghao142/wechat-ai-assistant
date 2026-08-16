package com.wechatai.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.session.entity.MessageEntity;
import com.wechatai.session.mapper.MessageMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史对话搜索工具 — 搜索当前会话的聊天记录，让 AI 能"想起来"之前聊过的内容。
 * <p>
 * sessionId 由 ToolExecuteNode 自动注入，无需 LLM 提供。
 */
@Component
public class ConversationSearchTool {

    private static final Logger log = LoggerFactory.getLogger(ConversationSearchTool.class);

    private static final int CONTEXT_WINDOW = 5;  // 命中消息前后各取 5 条
    private static final int MAX_HITS = 3;         // 最多返回 3 个命中片段
    private static final int MAX_SNIPPET_LEN = 200;

    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationSearchTool(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.objectMapper = new ObjectMapper();
    }

    @Tool("搜索历史对话记录。当用户问'我叫什么''我之前说过什么'等涉及之前聊天内容的问题时，必须调用此工具搜索。关键词越具体命中率越高")
    public String searchConversation(
            @P("搜索关键词，如文件名、话题名称、具体内容等") String keyword,
            String sessionId) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return "关键词不能为空";
        }
        if (sessionId == null) {
            return "会话 ID 不可用，无法搜索";
        }

        log.info("【对话搜索】keyword={}, sessionId={}", keyword, sessionId);

        // 1. 搜索当前 session 中包含关键词的消息
        List<MessageEntity> hits = messageMapper.searchByKeyword(sessionId, keyword, MAX_HITS);

        if (hits == null || hits.isEmpty()) {
            return "未找到包含「" + keyword + "」的历史对话记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到以下相关历史对话记录（越靠后越新）：\n\n");

        for (MessageEntity hit : hits) {
            Long hitId = hit.getId();
            String hitRole = roleDisplay(hit.getRole().name());
            String hitContent = truncate(hit.getContent(), MAX_SNIPPET_LEN);

            // 2. 取命中消息前后各 CONTEXT_WINDOW 条作为上下文
            List<MessageEntity> context = messageMapper.findRangeById(
                    hitId - CONTEXT_WINDOW, hitId + CONTEXT_WINDOW, sessionId);

            sb.append("--- 相关片段 ---\n");
            for (MessageEntity msg : context) {
                String role = roleDisplay(msg.getRole().name());
                String content = truncate(msg.getContent(), MAX_SNIPPET_LEN);
                // 标记命中的消息
                if (msg.getId().equals(hitId)) {
                    sb.append("→ [").append(role).append("] ").append(content).append(" ←\n");
                } else {
                    sb.append("  [").append(role).append("] ").append(content).append("\n");
                }
            }
            sb.append("\n");
        }

        String result = sb.toString();
        if (result.length() > 4000) {
            result = result.substring(0, 4000) + "\n\n...（以下已截断）";
        }

        log.info("【对话搜索】找到 {} 条命中，返回 {} 字", hits.size(), result.length());
        return result;
    }

    private String roleDisplay(String role) {
        return switch (role) {
            case "USER" -> "用户";
            case "ASSISTANT" -> "AI";
            case "TOOL" -> "工具结果";
            case "SYSTEM" -> "系统";
            default -> role;
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
