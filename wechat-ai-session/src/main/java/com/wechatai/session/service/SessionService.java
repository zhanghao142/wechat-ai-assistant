package com.wechatai.session.service;

import com.wechatai.session.entity.SessionEntity;
import com.wechatai.session.model.vo.SessionVO;

import java.util.List;

/**
 * 会话服务接口 — 跨模块只依赖此接口，不依赖实现。
 * <p>
 * 会话是一组消息的容器：LLM 本身无记忆，需把历史消息随请求一并发送才能保持上下文。
 * 每个会话默认 24 小时有效；微信用户无需手动创建，由
 * {@link #getOrCreateActiveSession(String)} 自动管理（有活跃则续期，无则新建）。
 * 「重新开始」场景应清空消息而非新建会话，避免用户看到一堆空白对话。
 */
public interface SessionService {

    /**
     * 获取或创建用户活跃会话。
     * <p>
     * 活跃定义：{@code status = ACTIVE} 且 {@code expire_at > NOW()}（由 MySQL 比较）。
     * 查到则 UPDATE 续期 24h；查不到则 INSERT 新会话。续期在发消息时顺带完成，不需要定时器。
     */
    SessionEntity getOrCreateActiveSession(String userId);

    /**
     * 多账号：获取或创建用户在指定 client 下的活跃会话。
     * <p>
     * wechatClientId 为 null 时等同于 {@link #getOrCreateActiveSession(String)}。
     */
    SessionEntity getOrCreateActiveSession(String userId, String wechatClientId);

    /**
     * 桌面端：确保客户端传入的 sessionId 在库中存在并挂到 userId。
     * <p>
     * 不存在则 INSERT；已存在则续期 24h。
     * 若原 user_id 为 {@code desktop-user}（或空），且当前 userId 为正式用户，则改绑。
     */
    SessionEntity ensureDesktopSession(String userId, String sessionId);

    /** 按 sessionId 查询详情（文档 3.2） */
    SessionVO getSession(String sessionId);

    /** 按用户列出会话（文档 3.3） */
    List<SessionVO> listByUserId(String userId);

    /** 更新会话标题（文档 3.4） */
    void updateTitle(String sessionId, String title);

    /** 软删除会话（文档 3.5） */
    void deleteSession(String sessionId);
}
