package com.wechatai.common.enums;

/**
 * 会话来源，标识会话由哪条接入链路创建。
 */
public enum SessionSource {
    WECHAT, // 微信接入（公众号/机器人）
    API     // 直接 HTTP API 调用
}
