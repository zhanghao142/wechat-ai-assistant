package com.wechatai.common.enums;

/**
 * 文档处理状态机。
 * <p>
 * 上传后为 {@code UPLOADED}，入队解析为 {@code PARSING}，
 * 成功为 {@code PARSED}（可供 AI 检索），失败为 {@code FAILED}。
 */
public enum FileStatus {
    UPLOADED, // 已上传，尚未解析
    PARSING,  // 解析中（Worker 处理中）
    PARSED,   // 解析完成，文本可用
    FAILED    // 解析失败
}
