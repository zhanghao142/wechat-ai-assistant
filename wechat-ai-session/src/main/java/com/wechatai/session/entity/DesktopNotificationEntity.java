package com.wechatai.session.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 桌面端通知 — 对应 desktop_notification 表。
 * 提醒等到期后写入，由桌面轮询拉取，不走 iLink。
 */
@Data
public class DesktopNotificationEntity {

    private Long id;
    private String notificationId;
    private String userId;
    private String sessionId;
    private String remindId;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
