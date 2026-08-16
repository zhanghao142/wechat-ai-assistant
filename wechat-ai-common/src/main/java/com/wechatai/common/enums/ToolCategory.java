package com.wechatai.common.enums;

/**
 * 工具分类，影响 AI 选工具策略与错误重试策略。
 * <ul>
 *   <li>{@link #READ}：无副作用，可并发、可自动重试，幂等</li>
 *   <li>{@link #WRITE}：有副作用，敏感操作需确认，失败不可盲目重试</li>
 *   <li>{@link #SYSTEM}：控制 AI 自身行为（追问、结束对话等），不操作外部系统</li>
 * </ul>
 */
public enum ToolCategory {
    READ,   // 查询类，可重试且幂等
    WRITE,  // 写入类，有副作用，需确认
    SYSTEM  // 系统类，控制 AI 自身
}
