package com.wechatai.ai.model.vo;

import com.wechatai.common.enums.MessageRole;
import com.wechatai.common.enums.MessageType;
import lombok.Data;

import java.util.List;

/**
 * 历史消息视图（文档 5.2）。
 */
@Data
public class HistoryMessageVO {

    private String messageId;
    private MessageRole role;
    private MessageType messageType;
    private String content;
    private List<String> fileIds;
    private List<ToolCallVO> toolCalls;
    private String finishReason;
    private String createdAt;

    /** 前端展示用：配文（不含 VL/解析内部描述）；为空则用 content */
    private String displayText;
    /** 图片可访问 URL（相对路径，如 /api/v1/chat/media/xxx.png） */
    private String mediaUrl;
    /** 文档原始文件名 */
    private String fileName;
}
