package com.wechatai.common.enums;

/**
 * 异步任务类型，决定入队到哪条 Redis 队列及由哪个 Worker 消费。
 */
public enum TaskType {
    DOC_PARSE,    // 文档解析（PDF/Word/TXT）
    TOOL_EXECUTE, // 工具异步执行（预留）
    AI_CHAT       // AI 对话异步处理（预留）
}
