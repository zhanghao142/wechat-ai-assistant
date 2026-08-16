package com.wechatai.tool.model.dto;

import lombok.Data;

import java.util.Map;

/**
 * 工具执行内部请求 DTO（不再作为 HTTP 入参）。
 * <p>
 * LangChain4j 自动调用时也可映射到此结构做日志与审计。
 */
@Data
public class ToolExecuteReq {

    private String toolName;
    private String toolCallId;
    private Map<String, Object> arguments;
    private String userId;
    private String sessionId;
}
