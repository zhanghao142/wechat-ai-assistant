package com.wechatai.wechat.multi;

/**
 * 消息发送接口 — 统一多账号模式下的消息发送。
 * <p>
 * 所有方法通过 conversationKey 定位目标 client 和用户。
 * 多账号模式使用此接口（通过 WechatClientRegistry 路由），
 * 单账号模式继续使用 {@link WechatOutboundSender}。
 */
public interface WechatMessageSender {

    /** 发送纯文本消息 */
    void sendText(String conversationKey, String text);

    /** 智能回复（解析音色标记 + TTS 语音） */
    void sendReply(String conversationKey, String reply);

    /** 发送文件 */
    void sendFile(String conversationKey, byte[] data, String fileName, String ext);

    /** 发送本地图片文件 */
    void sendImage(String conversationKey, String filePath);

    /** 发送错误提示 */
    void sendError(String conversationKey);
}
