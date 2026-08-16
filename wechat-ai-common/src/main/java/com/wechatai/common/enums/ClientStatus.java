package com.wechatai.common.enums;

/**
 * 微信 Bot 客户端生命周期状态。
 * <p>
 * 状态机流转：
 * <pre>
 *   CREATED → WAITING_FOR_SCAN → LOGGED_IN → RUNNING
 *                                          ↘ RECONNECTING → RUNNING / NEEDS_RELOGIN
 *                                          ↗ CLOSED
 * </pre>
 */
public enum ClientStatus {

    /** 刚创建，尚未启动登录流程 */
    CREATED,

    /** 已生成二维码，等待用户扫码 */
    WAITING_FOR_SCAN,

    /** 扫码成功，但尚未进入消息循环 */
    LOGGED_IN,

    /** 正常运行，心跳正常，接收消息 */
    RUNNING,

    /** 心跳异常，正在尝试重连 */
    RECONNECTING,

    /** 重连失败超限，需要重新扫码登录 */
    NEEDS_RELOGIN,

    /** 已关闭，不再接收消息 */
    CLOSED
}
