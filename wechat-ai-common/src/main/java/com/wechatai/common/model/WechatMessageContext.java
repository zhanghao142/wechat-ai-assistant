package com.wechatai.common.model;

/**
 * 微信消息上下文 — 多账号架构下全链路传递的不可变数据载体。
 * <p>
 * 替代原来零散的 {@code fromUser} 字符串参数，将 Bot 标识和用户标识统一封装，
 * 贯穿 Dispatcher → InboundHandler → ChatBridge → OutboundSender 全链路。
 * <p>
 * {@code conversationKey} 为预计算的 {@code clientId + ":" + fromUserId} 复合键，
 * 用于 AI 对话隔离和执行器线程池路由。
 *
 * @param clientId       Bot 客户端标识
 * @param botId          iLink SDK 的 botId（用于日志/排查）
 * @param fromUserId     微信用户 ID（消息发送者）
 * @param conversationKey 复合隔离键 = clientId:fromUserId
 * @param content        消息文本内容
 * @param messageId      微信消息 ID（用于幂等去重）
 */
public record WechatMessageContext(
        String clientId,
        String botId,
        String fromUserId,
        String conversationKey,
        String content,
        String messageId
) {

    /**
     * 为单账号模式构建默认上下文（clientId="default"，conversationKey=fromUserId）。
     */
    public static WechatMessageContext singleAccount(String fromUserId, String content, String messageId) {
        return new WechatMessageContext("default", null, fromUserId, fromUserId, content, messageId);
    }

    /**
     * 为多账号模式构建上下文。
     */
    public static WechatMessageContext multiAccount(String clientId, String botId, String fromUserId,
                                                     String content, String messageId) {
        String convKey = clientId + ":" + fromUserId;
        return new WechatMessageContext(clientId, botId, fromUserId, convKey, content, messageId);
    }
}
