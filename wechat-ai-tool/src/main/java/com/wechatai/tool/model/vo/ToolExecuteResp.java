package com.wechatai.tool.model.vo;

import lombok.Data;

/**
 * 工具执行内部响应。
 * <p>
 * 失败时 {@link ToolError#retryable} 决定 AI 策略：
 * true → 引擎可自动重试（如未解析完、超时）；false → 直接向用户说明（如参数错误、无权限）。
 */
@Data
public class ToolExecuteResp {

    private String toolName;
    private String toolCallId;
    private boolean success;
    private Object result;
    private ToolError error;
    private Long latencyMs;

    @Data
    public static class ToolError {
        private String code;
        private String message;
        /** 是否允许 AI 自动重试 */
        private boolean retryable;
    }

    public static ToolExecuteResp ok(String toolName, String toolCallId, Object result, long latency) {
        ToolExecuteResp resp = new ToolExecuteResp();
        resp.setToolName(toolName);
        resp.setToolCallId(toolCallId);
        resp.setSuccess(true);
        resp.setResult(result);
        resp.setLatencyMs(latency);
        return resp;
    }

    public static ToolExecuteResp fail(String toolName, String toolCallId,
                                       String code, String message, boolean retryable, long latency) {
        ToolExecuteResp resp = new ToolExecuteResp();
        resp.setToolName(toolName);
        resp.setToolCallId(toolCallId);
        resp.setSuccess(false);
        ToolError error = new ToolError();
        error.setCode(code);
        error.setMessage(message);
        error.setRetryable(retryable);
        resp.setError(error);
        resp.setLatencyMs(latency);
        return resp;
    }
}
