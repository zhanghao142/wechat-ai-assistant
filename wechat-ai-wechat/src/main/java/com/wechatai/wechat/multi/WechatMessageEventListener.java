package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.wechatai.common.model.ConversationKey;
import com.wechatai.wechat.event.WechatInboundBatchEvent;
import com.wechatai.wechat.message.WechatInboundHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 多账号消息事件监听器 — 替换单账号的 {@link WechatMessageDispatcher} 直调。
 * <p>
 * 仅在 {@code wechat.multi-account.enabled=true} 时激活。
 * 监听 {@link WechatInboundBatchEvent}，将消息按类型分发给 {@link WechatInboundHandler}。
 * 同时记录 userId → clientId 映射，确保回复时用正确的 Bot 发送。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "true")
public class WechatMessageEventListener {

    private final WechatInboundHandler inboundHandler;
    private final WechatClientRegistry clientRegistry;

    public WechatMessageEventListener(WechatInboundHandler inboundHandler,
                                       WechatClientRegistry clientRegistry) {
        this.inboundHandler = inboundHandler;
        this.clientRegistry = clientRegistry;
    }

    @EventListener
    public void onInboundBatch(WechatInboundBatchEvent event) {
        String clientId = event.getClientId();
        for (WeixinMessage msg : event.getMessages()) {
            String fromUser = msg.getFrom_user_id();
            // 记录 user → client 映射，确保回复用正确的 Bot
            clientRegistry.recordUserClient(fromUser, clientId);
            String conversationKey = ConversationKey.of(clientId, fromUser);

            if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
                continue;
            }

            for (MessageItem item : msg.getItem_list()) {
                dispatchItem(conversationKey, item);
            }
        }
    }

    private void dispatchItem(String conversationKey, MessageItem item) {
        try {
            switch (item.getType()) {
                case 1 -> {
                    if (item.getText_item() != null) {
                        inboundHandler.handleTextByKey(conversationKey, item.getText_item().getText());
                    }
                }
                case 2 -> {
                    if (item.getImage_item() != null) {
                        inboundHandler.handleImageByKey(conversationKey, item);
                    }
                }
                case 3 -> {
                    if (item.getVoice_item() != null) {
                        inboundHandler.handleVoiceByKey(conversationKey, item);
                    }
                }
                case 4 -> {
                    if (item.getFile_item() != null) {
                        inboundHandler.handleFileByKey(conversationKey, item);
                    }
                }
                default -> log.debug("未知消息类型: type={}", item.getType());
            }
        } catch (Exception e) {
            log.error("多账号消息分发失败 key={}", conversationKey, e);
        }
    }
}
