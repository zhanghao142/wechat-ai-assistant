package com.wechatai.common.enums;

/**
 * 消息内容类型，区分文本/多媒体以及工具调用相关消息。
 */
public enum MessageType {
    TEXT,        // 纯文本
    IMAGE,       // 图片
    FILE,        // 文件（文档上传等）
    TOOL_CALL,   // 模型发起的工具调用
    TOOL_RESULT  // 工具执行结果回填
}
