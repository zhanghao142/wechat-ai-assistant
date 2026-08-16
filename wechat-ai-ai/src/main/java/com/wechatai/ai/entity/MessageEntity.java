package com.wechatai.ai.entity;

import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageEntity {
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