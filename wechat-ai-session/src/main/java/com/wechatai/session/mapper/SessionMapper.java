package com.wechatai.session.mapper;

import com.wechatai.session.entity.SessionEntity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话表 MyBatis Mapper。
 * <p>
 * {@link #findActiveByUserId} 与 {@link #refreshExpireTime} 配合实现
 * getOrCreateActiveSession：先查活跃再续期，无需应用层定时器。
 */
@Mapper
public interface SessionMapper {

    /**
     * 查询用户当前活跃会话：status=ACTIVE 且 expire_at > NOW()，
     * 按 created_at 降序取一条。
     */
    @Select("""
        SELECT * FROM session
        WHERE user_id = #{userId}
          AND status = 'ACTIVE'
          AND expire_at > NOW()
        ORDER BY created_at DESC
        LIMIT 1
    """)
    SessionEntity findActiveByUserId(String userId);

    /**
     * 多账号：查询用户在指定 client 下的活跃会话。
     */
    @Select("""
        <script>
        SELECT * FROM session
        WHERE user_id = #{userId}
          <if test="wechatClientId != null">AND wechat_client_id = #{wechatClientId}</if>
          AND status = 'ACTIVE'
          AND expire_at &gt; NOW()
        ORDER BY created_at DESC
        LIMIT 1
        </script>
    """)
    SessionEntity findActiveByUserAndClient(@Param("userId") String userId,
                                             @Param("wechatClientId") String wechatClientId);

    /** 将会话 expire_at 续期为指定时间（通常 NOW()+24h） */
    @Update("UPDATE session SET expire_at = #{newExpireAt} WHERE session_id = #{sessionId}")
    void refreshExpireTime(@Param("sessionId") String sessionId,
                           @Param("newExpireAt") LocalDateTime newExpireAt);

    @Select("SELECT * FROM session WHERE session_id = #{sessionId}")
    SessionEntity findBySessionId(String sessionId);

    @Insert("""
        <script>
        INSERT INTO session (session_id, user_id
          <if test="wechatClientId != null">, wechat_client_id</if>
          , source, status, expire_at)
        VALUES (#{sessionId}, #{userId}
          <if test="wechatClientId != null">, #{wechatClientId}</if>
          , #{source}, 'ACTIVE', #{expireAt})
        </script>
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SessionEntity entity);

    @Update("UPDATE session SET title = #{title} WHERE session_id = #{sessionId}")
    int updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);

    /** 软删除：将状态置为 DELETED */
    @Update("UPDATE session SET status = 'DELETED' WHERE session_id = #{sessionId}")
    int softDelete(String sessionId);

    @Select("""
        SELECT * FROM session
        WHERE user_id = #{userId}
          AND (status IS NULL OR status <> 'DELETED')
        ORDER BY COALESCE(last_message_at, updated_at, created_at) DESC
        """)
    List<SessionEntity> findByUserId(String userId);

    /**
     * 桌面会话：续期，并在需要时改绑 user_id（如从 desktop-user 迁到登录用户）。
     */
    @Update("""
        UPDATE session
        SET expire_at = #{newExpireAt},
            user_id = #{userId},
            status = 'ACTIVE',
            updated_at = NOW()
        WHERE session_id = #{sessionId}
        """)
    int bindUserAndRefresh(@Param("sessionId") String sessionId,
                           @Param("userId") String userId,
                           @Param("newExpireAt") LocalDateTime newExpireAt);
}
