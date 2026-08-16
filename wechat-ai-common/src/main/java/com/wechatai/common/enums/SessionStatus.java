package com.wechatai.common.enums;

/**
 * 会话生命周期状态。
 * <p>
 * 活跃判断以库表 {@code expire_at > NOW()} 为准；
 * {@code EXPIRED}/{@code DELETED} 用于展示与软删，不再承接新消息。
 */
public enum SessionStatus {
    ACTIVE,   // 有效，可继续对话
    EXPIRED,  // 已过期（超过有效期）
    DELETED   // 已软删除
}
