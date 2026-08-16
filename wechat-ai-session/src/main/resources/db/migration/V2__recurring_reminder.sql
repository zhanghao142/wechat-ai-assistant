-- V2__recurring_reminder.sql
-- 周期提醒支持：为 remind 表添加重复提醒相关字段

ALTER TABLE remind
    ADD COLUMN repeat_type VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '重复类型：NONE-不重复, DAILY-每天, WEEKLY-每周, MONTHLY-每月, INTERVAL-每N天',
    ADD COLUMN repeat_value VARCHAR(50) NULL COMMENT '重复值：WEEKLY时为星期几(1-7)逗号分隔, MONTHLY时为日期(1-31)逗号分隔, INTERVAL时为天数',
    ADD COLUMN repeat_end_at DATETIME NULL COMMENT '重复截止日期，NULL表示永久重复',
    ADD COLUMN repeat_count INT NOT NULL DEFAULT 0 COMMENT '已重复次数';

-- 为周期提醒添加索引（用于快速查询活跃的周期提醒）
CREATE INDEX idx_remind_recurring_active
    ON remind (status, repeat_type, remind_at);
