package com.wechatai.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 发送对话请求体（文档 5.1）。
 * <p>
 * sessionId 可由上游传入；若走微信链路，也可在服务内通过
 * getOrCreateActiveSession 得到活跃会话后再处理。
 */
@Data
public class ChatSendReq {

    @NotBlank
    private String sessionId;

    @NotBlank
    private String userId;

    /** 用户文本内容 */
    @NotBlank
    private String message;

    /** 消息类型，默认 TEXT */
    private String messageType = "TEXT";

    /** 可选：关联已上传文档，用于限定检索范围 */
    private List<String> fileIds;

    /** 扩展字段（渠道、调试开关等） */
    private Map<String, Object> extra;
}
