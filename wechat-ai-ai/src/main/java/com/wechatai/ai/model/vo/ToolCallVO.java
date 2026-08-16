package com.wechatai.ai.model.vo;

import lombok.Data;

/**
 * 工具调用视图 — 用于历史消息展示。
 */
@Data
public class ToolCallVO {

    private String toolName;
    private String toolCallId;
    private String arguments;
}
