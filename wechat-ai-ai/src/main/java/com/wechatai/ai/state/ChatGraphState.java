package com.wechatai.ai.state;

import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LangGraph 图状态 — 对应 demo 的 {@code AppState}。
 * <p>
 * 各节点通过 {@code Map<String, Object>} 增量更新状态，此处提供类型化读写。
 */
public class ChatGraphState extends AgentState {

    public static final String KEY_USER_ID = "userId";
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_USER_MESSAGE = "userMessage";
    public static final String KEY_MESSAGE_TYPE = "messageType";
    public static final String KEY_FILE_IDS = "fileIds";
    public static final String KEY_REPLY = "reply";
    public static final String KEY_NEED_TOOL = "needTool";
    public static final String KEY_TOOL_EXECUTION_REQUESTS = "toolExecutionRequests";
    public static final String KEY_MESSAGES = "messages";
    public static final String KEY_FINISH_REASON = "finishReason";
    /** 工具执行产生的附件文件列表，每个元素为 Map: {type, filePath, fileName, mimeType} */
    public static final String KEY_TOOL_ATTACHMENTS = "toolAttachments";
    /** 多账号模式下的客户端标识（单账号模式下不存在） */
    public static final String KEY_CLIENT_ID = "clientId";
    /** 自定义 system prompt（子 Agent 覆盖中央默认 prompt） */
    public static final String KEY_SYSTEM_PROMPT_OVERRIDE = "systemPromptOverride";
    /** 子 Agent 工具白名单（覆盖中央从 ToolRegistry 拉全量） */
    public static final String KEY_AGENT_TOOLS = "agentTools";
    /** 触达渠道：WECHAT / DESKTOP */
    public static final String KEY_CHANNEL = "channel";
    /**
     * 多子 Agent 并行且存在 WRITE 确认队列：tool_execute 后跳过中央 llm_think，直接结束本轮图。
     */
    public static final String KEY_AWAIT_PARALLEL_CONFIRM = "awaitParallelConfirm";

    public ChatGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public String getUserId() {
        return (String) data().get(KEY_USER_ID);
    }

    public String getClientId() {
        return (String) data().get(KEY_CLIENT_ID);
    }

    public String getSessionId() {
        return (String) data().get(KEY_SESSION_ID);
    }

    public String getChannel() {
        Object v = data().get(KEY_CHANNEL);
        return v != null ? v.toString() : null;
    }

    public String getUserMessage() {
        return (String) data().get(KEY_USER_MESSAGE);
    }

    public String getMessageType() {
        return (String) data().getOrDefault(KEY_MESSAGE_TYPE, "TEXT");
    }

    @SuppressWarnings("unchecked")
    public List<String> getFileIds() {
        return (List<String>) data().get(KEY_FILE_IDS);
    }

    public String getReply() {
        return (String) data().get(KEY_REPLY);
    }

    public boolean isNeedTool() {
        return Boolean.TRUE.equals(data().get(KEY_NEED_TOOL));
    }

    public String getFinishReason() {
        return (String) data().getOrDefault(KEY_FINISH_REASON, "STOP");
    }

    @SuppressWarnings("unchecked")
    public List<Object> getToolExecutionRequests() {
        return (List<Object>) data().get(KEY_TOOL_EXECUTION_REQUESTS);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getToolAttachments() {
        Object attachments = data().get(KEY_TOOL_ATTACHMENTS);
        if (!(attachments instanceof List)) {
            return new ArrayList<>();
        }
        return (List<Map<String, Object>>) attachments;
    }

    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages() {
        Object msgs = data().get(KEY_MESSAGES);
        if (!(msgs instanceof List)) {
            return new ArrayList<>();
        }
        return (List<ChatMessage>) msgs;
    }
}
