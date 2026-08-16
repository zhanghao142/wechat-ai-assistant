package com.wechatai.session.service;

import com.wechatai.common.enums.Channel;
import com.wechatai.session.entity.RemindEntity;
import com.wechatai.session.mapper.RemindMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 提醒服务实现（支持周期提醒、渠道分离）。
 */
@Service
public class RemindServiceImpl implements RemindService {

    private final RemindMapper remindMapper;

    public RemindServiceImpl(RemindMapper remindMapper) {
        this.remindMapper = remindMapper;
    }

    @Override
    public RemindEntity createRemind(String userId, String content, LocalDateTime remindAt) {
        return createRemind(userId, content, remindAt, "NONE", null, null, Channel.WECHAT.name(), null);
    }

    @Override
    public RemindEntity createRemind(String userId, String content, LocalDateTime remindAt,
                                     String repeatType, String repeatValue, LocalDateTime repeatEndAt) {
        return createRemind(userId, content, remindAt, repeatType, repeatValue, repeatEndAt,
                Channel.WECHAT.name(), null);
    }

    @Override
    public RemindEntity createRemind(String userId, String content, LocalDateTime remindAt,
                                     String repeatType, String repeatValue, LocalDateTime repeatEndAt,
                                     String channel, String sessionId) {
        RemindEntity entity = new RemindEntity();
        entity.setRemindId("remind_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setUserId(userId);
        entity.setChannel(Channel.from(channel).name());
        entity.setSessionId(sessionId);
        entity.setContent(content);
        entity.setRemindAt(remindAt);
        entity.setStatus("PENDING");
        entity.setRepeatType(repeatType != null ? repeatType : "NONE");
        entity.setRepeatValue(repeatValue);
        entity.setRepeatEndAt(repeatEndAt);
        entity.setRepeatCount(0);
        remindMapper.insert(entity);
        return entity;
    }

    @Override
    public List<RemindEntity> findPendingBefore(LocalDateTime now, int limit) {
        return remindMapper.findPendingBefore(now, limit);
    }

    @Override
    public void markSent(String remindId) {
        remindMapper.updateStatus(remindId, "SENT");
    }

    @Override
    public void updateForNextRepeat(String remindId, LocalDateTime nextTime) {
        remindMapper.updateForNextRepeat(remindId, nextTime);
    }

    @Override
    public void markCompleted(String remindId) {
        remindMapper.updateStatus(remindId, "COMPLETED");
    }
}
