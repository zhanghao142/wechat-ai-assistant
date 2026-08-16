package com.wechatai.common.enums;

/**
 * 消息/提醒触达渠道 — 入站与出站按渠道分叉，AI 编排共用。
 */
public enum Channel {

    /** 微信 iLink */
    WECHAT,

    /** 桌面端 REST */
    DESKTOP;

    /**
     * 解析渠道；空或未知时默认 WECHAT，保证旧数据与微信路径兼容。
     */
    public static Channel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WECHAT;
        }
        try {
            return Channel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return WECHAT;
        }
    }
}
