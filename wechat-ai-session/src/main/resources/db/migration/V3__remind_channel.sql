-- V3__remind_channel.sql
-- 提醒渠道分离：微信 / 桌面；桌面需带回 session_id
ALTER TABLE remind
  ADD COLUMN `channel` varchar(20) NOT NULL DEFAULT 'WECHAT'
    COMMENT '渠道：WECHAT / DESKTOP' AFTER `user_id`,
  ADD COLUMN `session_id` varchar(64) DEFAULT NULL
    COMMENT '桌面会话 ID；微信侧可为空' AFTER `channel`,
  ADD KEY `idx_remind_channel_pending` (`channel`, `status`, `remind_at`);

-- 桌面端待拉取通知（不走 iLink）
CREATE TABLE IF NOT EXISTS `desktop_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notification_id` varchar(50) NOT NULL COMMENT '通知业务 ID',
  `user_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `remind_id` varchar(50) DEFAULT NULL,
  `content` varchar(1000) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `read_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_id` (`notification_id`),
  KEY `idx_desktop_notif_user_unread` (`user_id`, `read_at`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
