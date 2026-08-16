package com.wechatai.session.service;

import com.wechatai.common.enums.SessionSource;
import com.wechatai.common.enums.SessionStatus;
import com.wechatai.session.entity.SessionEntity;
import com.wechatai.session.mapper.SessionMapper;
import com.wechatai.session.model.vo.SessionVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话服务实现。
 * <p>
 * 核心逻辑在 {@link #getOrCreateActiveSession}：
 * 通过 Mapper 查活跃会话（{@code expire_at > NOW()}），有则续期 24h，无则创建。
 * 不依赖定时扫库；过期会话在下次发消息时自然失效并新建。
 * AI / 微信模块只调用接口，不关心 SQL 细节。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private final SessionMapper sessionMapper;

    public SessionServiceImpl(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    @Override
    public SessionEntity getOrCreateActiveSession(String userId) {
        return getOrCreateActiveSession(userId, null);
    }

    @Override
    public SessionEntity getOrCreateActiveSession(String userId, String wechatClientId) {
        // ① 查活跃会话（数据库比较 expire_at > NOW()）
        SessionEntity session;
        if (wechatClientId != null) {
            session = sessionMapper.findActiveByUserAndClient(userId, wechatClientId);
        } else {
            session = sessionMapper.findActiveByUserId(userId);
        }

        if (session != null) {
            // ② 有活跃会话 → 续期 24 小时
            sessionMapper.refreshExpireTime(
                    session.getSessionId(),
                    LocalDateTime.now().plusHours(24)
            );
            session.setExpireAt(LocalDateTime.now().plusHours(24));
            return session;
        }

        // ③ 没有活跃会话 → 创建新的
        SessionEntity newSession = new SessionEntity();
        newSession.setSessionId(UUID.randomUUID().toString());
        newSession.setUserId(userId);
        newSession.setWechatClientId(wechatClientId);
        newSession.setSource(SessionSource.WECHAT);
        newSession.setStatus(SessionStatus.ACTIVE);
        newSession.setExpireAt(LocalDateTime.now().plusHours(24));
        sessionMapper.insert(newSession);
        return newSession;
    }

    @Override
    public SessionEntity ensureDesktopSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        LocalDateTime expireAt = LocalDateTime.now().plusHours(24);
        SessionEntity existing = sessionMapper.findBySessionId(sessionId);

        if (existing == null) {
            SessionEntity created = new SessionEntity();
            created.setSessionId(sessionId);
            created.setUserId(userId);
            created.setSource(SessionSource.API);
            created.setStatus(SessionStatus.ACTIVE);
            created.setExpireAt(expireAt);
            sessionMapper.insert(created);
            return created;
        }

        String owner = existing.getUserId();
        boolean guestOwner = owner == null || owner.isBlank() || "desktop-user".equals(owner);
        boolean shouldRebind = guestOwner && !"desktop-user".equals(userId) && !userId.equals(owner);
        String bindTo = shouldRebind ? userId : (owner != null && !owner.isBlank() ? owner : userId);

        sessionMapper.bindUserAndRefresh(sessionId, bindTo, expireAt);
        existing.setUserId(bindTo);
        existing.setExpireAt(expireAt);
        existing.setStatus(SessionStatus.ACTIVE);
        return existing;
    }

    @Override
    public SessionVO getSession(String sessionId) {
        SessionEntity entity = sessionMapper.findBySessionId(sessionId);
        return entity != null ? toVO(entity) : null;
    }

    @Override
    public List<SessionVO> listByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        List<SessionEntity> rows = sessionMapper.findByUserId(userId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        sessionMapper.updateTitle(sessionId, title);
    }

    @Override
    public void deleteSession(String sessionId) {
        sessionMapper.softDelete(sessionId);
    }

    private SessionVO toVO(SessionEntity entity) {
        SessionVO vo = new SessionVO();
        vo.setSessionId(entity.getSessionId());
        vo.setUserId(entity.getUserId());
        vo.setTitle(entity.getTitle());
        vo.setStatus(entity.getStatus());
        vo.setMessageCount(entity.getMessageCount());
        vo.setLastMessageAt(entity.getLastMessageAt() != null
                ? entity.getLastMessageAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null);
        vo.setCreatedAt(entity.getCreatedAt() != null
                ? entity.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null);
        vo.setExpireAt(entity.getExpireAt() != null
                ? entity.getExpireAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null);
        return vo;
    }
}
