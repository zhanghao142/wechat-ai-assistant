package com.wechatai.common.enums;

/**
 * 对话消息角色，对应写入 LLM 上下文时的角色划分。
 */
public enum MessageRole {
    USER,      // 用户输入
    ASSISTANT, // AI 回复
    SYSTEM,    // 系统提示（Prompt 预设等）
    TOOL       // 工具调用结果
}
