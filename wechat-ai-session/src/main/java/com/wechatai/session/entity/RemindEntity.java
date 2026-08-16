package com.wechatai.session.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒数据实体 — 对应 MySQL remind 表。
 * 用户通过 AI 设置定时提醒后，写入此表；RemindScheduler 扫描到期提醒，通过微信主动推送。
 */
@Data
public class RemindEntity {

    private Long id;
    private String remindId;
    private String userId;
    /** 触达渠道：WECHAT / DESKTOP，默认 WECHAT */
    private String channel;
    /** 桌面会话 ID；微信侧可为空 */
    private String sessionId;
    /** 微信客户端标识（多账号场景），单账号为 null */
    private String wechatClientId;
    private String content;
    private LocalDateTime remindAt;
    private String status;   // PENDING / SENT / CANCELLED / COMPLETED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 重复类型：NONE-不重复, DAILY-每天, WEEKLY-每周, MONTHLY-每月, INTERVAL-每N天 */
    private String repeatType;
    /** 重复值：WEEKLY时为逗号分隔的星期几(1-7), MONTHLY时为日期(1-31), INTERVAL时为天数 */
    private String repeatValue;
    /** 重复截止日期，NULL表示永久重复 */
    private LocalDateTime repeatEndAt;
    /** 已重复次数 */
    private Integer repeatCount;
}
