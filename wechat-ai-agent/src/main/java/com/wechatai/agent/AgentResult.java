package com.wechatai.agent;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent 的一次 invoke 结果。
 * <p>
 * ok：完成，summary 为 LLM 摘要。fail：异常，error 为错误信息。
 */
public class AgentResult {

    private final boolean ok;
    private final boolean needsConfirm;
    private final String summary;
    private final String error;
    private final int rounds;
    private final long elapsedMs;
    private final String confirmThreadId;
    private final String confirmToolName;
    private final java.util.List<java.util.Map<String, Object>> attachments;

    private AgentResult(boolean ok, boolean needsConfirm, String summary, String error,
                        int rounds, long elapsedMs, String confirmThreadId, String confirmToolName,
                        java.util.List<java.util.Map<String, Object>> attachments) {
        this.ok = ok;
        this.needsConfirm = needsConfirm;
        this.summary = summary;
        this.error = error;
        this.rounds = rounds;
        this.elapsedMs = elapsedMs;
        this.confirmThreadId = confirmThreadId;
        this.confirmToolName = confirmToolName;
        this.attachments = attachments;
    }

    public static AgentResult ok(String summary, int rounds, long elapsedMs) {
        return new AgentResult(true, false, summary, null, rounds, elapsedMs, null, null, null);
    }

    public static AgentResult ok(String summary, int rounds, long elapsedMs,
                                  java.util.List<java.util.Map<String, Object>> attachments) {
        return new AgentResult(true, false, summary, null, rounds, elapsedMs, null, null, attachments);
    }

    public static AgentResult fail(String error, int rounds, long elapsedMs) {
        return new AgentResult(false, false, null, error, rounds, elapsedMs, null, null, null);
    }

    /** WRITE 工具需要用户确认 */
    public static AgentResult confirm(String threadId, String toolName, String confirmMsg,
                                       int rounds, long elapsedMs) {
        return new AgentResult(false, true, confirmMsg, null, rounds, elapsedMs, threadId, toolName, null);
    }

    public boolean isOk() { return ok; }
    public boolean needsConfirm() { return needsConfirm; }
    public String getSummary() { return summary; }
    public String getError() { return error; }
    public int getRounds() { return rounds; }
    public long getElapsedMs() { return elapsedMs; }
    public String getConfirmThreadId() { return confirmThreadId; }
    public String getConfirmToolName() { return confirmToolName; }
    public java.util.List<java.util.Map<String, Object>> getAttachments() { return attachments; }
}
