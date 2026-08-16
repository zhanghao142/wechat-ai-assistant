package com.wechatai.wechat.event;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 微信消息批次事件 — 多账号模式下解耦 SDK 轮询线程与消息处理。
 * <p>
 * 由 {@code WechatClientRegistry} 中每个 Bot 的 {@code onMessage} 回调发布，
 * 由 {@code @EventListener} 方法消费，替代单账号模式下的同步 Dispatcher 调用。
 */
public class WechatInboundBatchEvent extends ApplicationEvent {

    private final String clientId;
    private final List<WeixinMessage> messages;

    public WechatInboundBatchEvent(Object source, String clientId, List<WeixinMessage> messages) {
        super(source);
        this.clientId = clientId;
        this.messages = messages;
    }

    public String getClientId() {
        return clientId;
    }

    public List<WeixinMessage> getMessages() {
        return messages;
    }
}
