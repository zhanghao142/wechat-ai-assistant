package com.wechatai.common.enums;

/**
 * 异步任务执行状态，供轮询与运维概览使用。
 */
public enum TaskStatus {
    QUEUED,  // 已入队，等待 Worker
    RUNNING, // 执行中
    DONE,    // 成功完成
    FAILED   // 失败（可按策略重试）
}
