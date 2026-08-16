package com.wechatai.ai.remind;

import com.wechatai.ai.service.ChatService;
import com.wechatai.session.entity.RemindEntity;
import com.wechatai.session.service.DesktopNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 桌面渠道提醒出站 — 落库会话消息 + 待拉取通知，不走 iLink。
 */
@Service
public class DesktopRemindDelivery {

    private static final Logger log = LoggerFactory.getLogger(DesktopRemindDelivery.class);

    private final ChatService chatService;
    private final DesktopNotificationService notificationService;

    public DesktopRemindDelivery(ChatService chatService,
                                 DesktopNotificationService notificationService) {
        this.chatService = chatService;
        this.notificationService = notificationService;
    }

    /**
     * @return false 表示缺少 sessionId，调用方应记失败日志
     */
    public boolean deliver(RemindEntity remind) {
        String sessionId = remind.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.error("⏰ 桌面提醒缺少 sessionId, remindId={}", remind.getRemindId());
            return false;
        }
        String text = "🔔 提醒：" + remind.getContent();
        String msgId = "msg_a_rm_" + UUID.randomUUID().toString().substring(0, 8);
        chatService.saveAssistantMessage(msgId, sessionId, text, null, "REMIND", 0);
        notificationService.create(
                remind.getUserId(), sessionId, remind.getRemindId(), text);
        log.info("⏰ 桌面提醒已落库: remindId={}, sessionId={}, userId={}",
                remind.getRemindId(), sessionId, remind.getUserId());
        return true;
    }
}
