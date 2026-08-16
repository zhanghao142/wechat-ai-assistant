package com.wechatai.wechat.multi;

/**
 * AI 对话入口接口 — 统一多账号和单账号模式的消息处理入口。
 */
public interface WechatConversationGateway {

    /**
     * 异步处理 AI 对话（提交到用户线程池）。
     *
     * @param conversationKey 复合隔离键 = clientId:fromUserId
     * @param content         消息内容
     * @param messageType     消息类型（TEXT/IMAGE/VOICE/FILE）
     */
    void think(String conversationKey, String content, String messageType);

    /**
     * 在当前线程上同步处理 AI 对话。
     */
    void thinkOnCurrentThread(String conversationKey, String content, String messageType);

    /**
     * 获取指定 conversationKey 的专属单线程执行器。
     */
    java.util.concurrent.ExecutorService getExecutor(String conversationKey);
}
