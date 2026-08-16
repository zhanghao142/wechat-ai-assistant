package com.wechatai.session.entity;

import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息数据实体 — 对应 MySQL message 表
 */
@Data
public class MessageEntity {

    private Long id;
    private String messageId;
    private String sessionId;
    private MessageRole role;
    private MessageType messageType;
    private String content;
    private String fileIds;
    private String toolCalls;
    private String finishReason;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
