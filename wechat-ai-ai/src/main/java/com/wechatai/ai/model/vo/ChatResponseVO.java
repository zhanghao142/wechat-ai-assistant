package com.wechatai.ai.model.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 对话发送响应（文档 5.1，v1.1 精简版）。
 * <p>
 * 一次请求直接返回最终回复；不再包含 toolCalls/replyType/thought。
 * finishReason 对外一般为 STOP 或 ERROR。
 */
@Data
public class ChatResponseVO {

    /** AI 最终回复文本 */
    private String replyMessage;
    /** 本条助手消息 ID */
    private String messageId;
    private String sessionId;
    /** 结束原因：对外 STOP / ERROR / NEED_CONFIRM */
    private String finishReason;
    /** 端到端耗时（毫秒） */
    private Long latencyMs;
    /** 图片消息时的 Qwen-VL 识别摘要（仅 /chat/image） */
    private String imageDescription;
    /** 文档消息时的 fileId（仅 /chat/file） */
    private String fileId;
    /** 文档原始文件名 */
    private String fileName;
    /** 文档解析状态：PARSED / FAILED / ... */
    private String fileStatus;
    /** WRITE 待确认时的子 Agent threadId（桌面续跑用） */
    private String confirmThreadId;
    /**
     * 助手侧工具产物（截图 / 生成文档），供桌面预览下载。
     * 元素字段：type(image|file)、url、fileName（可选 filePath 仅服务端用）
     */
    private List<Map<String, Object>> attachments;
}
