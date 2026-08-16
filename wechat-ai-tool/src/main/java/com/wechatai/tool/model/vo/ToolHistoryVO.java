package com.wechatai.tool.model.vo;

import lombok.Data;

/**
 * 工具调用历史列表项（运维/回放用）。
 */
@Data
public class ToolHistoryVO {

    private String toolCallId;
    private String toolName;
    /** 入参摘要（JSON 字符串） */
    private String arguments;
    private boolean success;
    /** 结果摘要，避免回传过大正文 */
    private String resultSummary;
    private Long latencyMs;
    private String calledAt;
}
