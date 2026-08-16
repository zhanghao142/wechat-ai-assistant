package com.wechatai.session.service;

import com.wechatai.session.entity.DesktopNotificationEntity;
import com.wechatai.session.mapper.DesktopNotificationMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DesktopNotificationService {

    private final DesktopNotificationMapper mapper;

    public DesktopNotificationService(DesktopNotificationMapper mapper) {
        this.mapper = mapper;
    }

    public DesktopNotificationEntity create(String userId, String sessionId,
                                            String remindId, String content) {
        DesktopNotificationEntity entity = new DesktopNotificationEntity();
        entity.setNotificationId("dn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setRemindId(remindId);
        entity.setContent(content);
        mapper.insert(entity);
        return entity;
    }

    public List<DesktopNotificationEntity> listUnread(String userId, int limit) {
        return mapper.findUnreadByUser(userId, limit);
    }

    public void markRead(String notificationId, String userId) {
        mapper.markRead(notificationId, userId);
    }

    public void markAllRead(String userId) {
        mapper.markAllRead(userId);
    }
}
