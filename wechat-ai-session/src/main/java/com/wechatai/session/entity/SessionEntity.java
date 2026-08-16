package com.wechatai.session.entity;

import com.wechatai.common.enums.SessionSource;
import com.wechatai.common.enums.SessionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 MySQL {@code session} 表的持久化实体。
 * <p>
 * 活跃判断依赖 {@link #expireAt}；业务键为 {@link #sessionId}（UUID），
 * {@link #id} 为自增主键。
 */
@Data
public class SessionEntity {

    /** 自增主键 */
    private Long id;
    /** 业务会话 ID（UUID） */
    private String sessionId;
    /** 用户标识（如微信 openid） */
    private String userId;
    /** 微信客户端标识（多账号场景），单账号为 null */
    private String wechatClientId;
    /** 会话标题，可由首条消息或运营后台设置 */
    private String title;
    /** 来源：WECHAT / API */
    private SessionSource source;
    /** 状态：ACTIVE / EXPIRED / DELETED */
    private SessionStatus status;
    /** 消息条数统计 */
    private Integer messageCount;
    /** 最近一条消息时间 */
    private LocalDateTime lastMessageAt;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 过期时间；活跃条件为 expire_at > NOW() */
    private LocalDateTime expireAt;
    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
