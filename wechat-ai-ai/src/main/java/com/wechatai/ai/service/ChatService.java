package com.wechatai.ai.service;

import com.wechatai.ai.entity.MessageEntity;
import com.wechatai.ai.mapper.MessageMapper;
import com.wechatai.ai.model.vo.HistoryMessageVO;
import com.wechatai.ai.model.vo.ToolCallVO;
import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息持久化与历史查询服务。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Qualifier("aiMessageMapper")
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 持久化用户消息。
     */
    public MessageEntity saveUserMessage(String messageId, String sessionId, String userId,
                                         String content, List<String> fileIds) {
        return saveUserMessage(messageId, sessionId, userId, content, fileIds, MessageType.TEXT);
    }

    /**
     * 持久化用户消息（可指定类型，便于历史还原图片/文件附件）。
     */
    public MessageEntity saveUserMessage(String messageId, String sessionId, String userId,
                                         String content, List<String> fileIds, MessageType messageType) {
        MessageEntity msg = new MessageEntity();
        msg.setMessageId(messageId);
        msg.setSessionId(sessionId);
        msg.setRole(MessageRole.USER);
        msg.setMessageType(messageType != null ? messageType : MessageType.TEXT);
        msg.setContent(content);
        msg.setFileIds(fileIds != null ? toJson(fileIds) : null);
        messageMapper.insert(msg);
        return msg;
    }

    /**
     * 持久化助手回复消息。
     */
    public MessageEntity saveAssistantMessage(String messageId, String sessionId,
                                              String content, String toolCallsJson,
                                              String finishReason, int latencyMs) {
        MessageEntity msg = new MessageEntity();
        msg.setMessageId(messageId);
        msg.setSessionId(sessionId);
        msg.setRole(MessageRole.ASSISTANT);
        msg.setMessageType(MessageType.TEXT);
        msg.setContent(content);
        msg.setToolCalls(toolCallsJson);
        msg.setFinishReason(finishReason);
        msg.setLatencyMs(latencyMs);
        messageMapper.insert(msg);
        return msg;
    }

    /**
     * 获取会话历史消息（文档 5.2）。
     */
    public List<HistoryMessageVO> getHistory(String sessionId) {
        List<MessageEntity> entities = messageMapper.findBySessionId(sessionId);
        return entities.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * 清空会话上下文（文档 5.3）。
     */
    public void clearSession(String sessionId) {
        messageMapper.deleteBySessionId(sessionId);
        log.info("已清空会话 {} 的消息历史", sessionId);
    }

    // ── 内部转换 ──────────────────────────────────────────────

    private HistoryMessageVO toVO(MessageEntity entity) {
        HistoryMessageVO vo = new HistoryMessageVO();
        vo.setMessageId(entity.getMessageId());
        vo.setRole(entity.getRole());
        vo.setMessageType(entity.getMessageType());
        vo.setContent(entity.getContent());
        vo.setFileIds(fromJson(entity.getFileIds(), new TypeReference<List<String>>() {}));
        vo.setToolCalls(fromJson(entity.getToolCalls(), new TypeReference<List<ToolCallVO>>() {}));
        vo.setFinishReason(entity.getFinishReason());
        vo.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DTF) : null);
        return vo;
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return null; }
    }

    private <T> T fromJson(String json, TypeReference<T> ref) {
        if (json == null || json.isEmpty()) return null;
        try { return objectMapper.readValue(json, ref); }
        catch (Exception e) { return null; }
    }
}
