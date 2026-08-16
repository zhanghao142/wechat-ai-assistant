package com.wechatai.wechat.message;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 消息分发器 — 按 item.type 路由到对应的 WechatInboundHandler 方法。
 * <p>
 * 此组件只负责路由，不包含业务处理逻辑。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "false", matchIfMissing = true)
public class WechatMessageDispatcher {

    private final WechatInboundHandler inboundHandler;
    private final WechatOutboundSender sender;

    public WechatMessageDispatcher(WechatInboundHandler inboundHandler, WechatOutboundSender sender) {
        this.inboundHandler = inboundHandler;
        this.sender = sender;
    }

    /**
     * 分发一批消息。
     */
    public void dispatchMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String fromUser = msg.getFrom_user_id();
            if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
                continue;
            }
            for (MessageItem item : msg.getItem_list()) {
                dispatchItem(fromUser, item);
            }
        }
    }

    /**
     * 分发单条消息，⚠️ 必须用 item.type 判断消息类型，不能用顶层的 message_type 字段。
     */
    private void dispatchItem(String fromUser, MessageItem item) {
        try {
            switch (item.getType()) {
                case 1 -> {
                    if (item.getText_item() != null) {
                        inboundHandler.handleText(fromUser, item.getText_item().getText());
                    }
                }
                case 2 -> {
                    if (item.getImage_item() != null) {
                        inboundHandler.handleImage(fromUser, item);
                    }
                }
                case 3 -> {
                    if (item.getVoice_item() != null) {
                        inboundHandler.handleVoice(fromUser, item);
                    }
                }
                case 4 -> {
                    if (item.getFile_item() != null) {
                        inboundHandler.handleFile(fromUser, item);
                    }
                }
                default -> log.debug("未知消息类型: type={}", item.getType());
            }
        } catch (Exception e) {
            log.error("处理消息失败 fromUser={}", fromUser, e);
            sender.sendError(fromUser);
        }
    }
}
