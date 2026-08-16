-- V1__multi_account.sql
-- 多账号支持：为 session 和 remind 表添加 wechat_client_id 隔离列
-- 所有新列为 NULL 兼容单账号模式，现有数据不受影响

ALTER TABLE session
    ADD COLUMN wechat_client_id VARCHAR(36) NULL AFTER user_id;

ALTER TABLE remind
    ADD COLUMN wechat_client_id VARCHAR(36) NULL AFTER user_id;

-- 多账号下的复合查询索引
CREATE INDEX idx_session_client_user_active
    ON session (wechat_client_id, user_id, status, expire_at);

CREATE INDEX idx_remind_client_pending
    ON remind (wechat_client_id, status, remind_at);

-- 可选：为历史数据回填默认 clientId（取消注释按需执行）
-- UPDATE session SET wechat_client_id = 'default' WHERE source = 'WECHAT' AND wechat_client_id IS NULL;
-- UPDATE remind SET wechat_client_id = 'default' WHERE wechat_client_id IS NULL;
