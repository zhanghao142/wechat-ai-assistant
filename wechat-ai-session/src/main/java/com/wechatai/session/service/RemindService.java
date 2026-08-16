package com.wechatai.session.service;

import com.wechatai.session.entity.RemindEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒服务接口 — 设置与查询定时提醒（支持周期提醒、多渠道）。
 */
public interface RemindService {

    RemindEntity createRemind(String userId, String content, LocalDateTime remindAt);

    RemindEntity createRemind(String userId, String content, LocalDateTime remindAt,
                              String repeatType, String repeatValue, LocalDateTime repeatEndAt);

    /**
     * 创建提醒（带渠道与会话）。
     *
     * @param channel   WECHAT / DESKTOP
     * @param sessionId 桌面会话 ID；微信可 null
     */
    RemindEntity createRemind(String userId, String content, LocalDateTime remindAt,
                              String repeatType, String repeatValue, LocalDateTime repeatEndAt,
                              String channel, String sessionId);

    List<RemindEntity> findPendingBefore(LocalDateTime now, int limit);

    void markSent(String remindId);

    void updateForNextRepeat(String remindId, LocalDateTime nextTime);

    void markCompleted(String remindId);
}
