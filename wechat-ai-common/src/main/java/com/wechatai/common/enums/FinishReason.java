package com.wechatai.common.enums;

/**
 * 对话结束原因。
 * <p>
 * 对外响应仅暴露 {@link #STOP} / {@link #ERROR}；
 * {@link #TOOL}、{@link #LENGTH} 仅供引擎内部流转与落库，不对调用方展示。
 */
public enum FinishReason {
    STOP,   // 正常结束（对外）
    TOOL,   // 因工具调用中断（内部）
    LENGTH, // 因长度截断（内部）
    ERROR   // 异常结束（对外）
}
