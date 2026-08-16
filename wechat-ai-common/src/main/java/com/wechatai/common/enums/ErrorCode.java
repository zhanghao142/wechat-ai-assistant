package com.wechatai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局业务错误码，与接口文档「错误码表」一一对应。
 * <p>
 * 分组约定：通用（HTTP 风格）/ 会话 1xxx / 文档 2xxx / AI 3xxx / 微信 4xxx / 任务 5xxx。
 * Controller 与异常处理应优先使用本枚举，避免魔法数字散落。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ---------- 通用 ----------
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal error"),

    // ---------- 会话 1xxx ----------
    SESSION_NOT_FOUND(1001, "session not found"),
    SESSION_EXPIRED(1002, "session expired"),

    // ---------- 文档 2xxx ----------
    FILE_TOO_LARGE(2001, "file too large"),
    UNSUPPORTED_FILE_TYPE(2002, "unsupported file type"),
    FILE_PARSE_FAILED(2003, "file parse failed"),
    FILE_NOT_FOUND(2004, "file not found"),

    // ---------- AI 3xxx ----------
    AI_SERVICE_ERROR(3001, "ai service error"),
    TOOL_EXECUTION_FAILED(3002, "tool execution failed"),
    CONTEXT_OVERFLOW(3003, "context overflow"),

    // ---------- 微信 4xxx ----------
    WECHAT_MESSAGE_ERROR(4001, "wechat message error"),

    // ---------- 任务 5xxx ----------
    TASK_NOT_FOUND(5001, "task not found"),
    TASK_QUEUE_FULL(5002, "task queue full");

    private final int code;
    private final String message;
}
