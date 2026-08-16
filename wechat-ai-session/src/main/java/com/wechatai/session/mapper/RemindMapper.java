package com.wechatai.session.mapper;

import com.wechatai.session.entity.RemindEntity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提醒表 MyBatis Mapper。
 */
@Mapper
public interface RemindMapper {

    @Insert("""
        <script>
        INSERT INTO remind (remind_id, user_id, channel
          <if test="sessionId != null">, session_id</if>
          <if test="wechatClientId != null">, wechat_client_id</if>
          , content, remind_at, status, created_at
          <if test="repeatType != null">, repeat_type</if>
          <if test="repeatValue != null">, repeat_value</if>
          <if test="repeatEndAt != null">, repeat_end_at</if>
          , repeat_count)
        VALUES (#{remindId}, #{userId}, IFNULL(#{channel}, 'WECHAT')
          <if test="sessionId != null">, #{sessionId}</if>
          <if test="wechatClientId != null">, #{wechatClientId}</if>
          , #{content}, #{remindAt}, 'PENDING', NOW()
          <if test="repeatType != null">, #{repeatType}</if>
          <if test="repeatValue != null">, #{repeatValue}</if>
          <if test="repeatEndAt != null">, #{repeatEndAt}</if>
          , IFNULL(#{repeatCount}, 0))
        </script>
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RemindEntity entity);

    @Select("""
        SELECT * FROM remind
        WHERE status = 'PENDING'
          AND remind_at <= #{now}
        ORDER BY remind_at ASC
        LIMIT #{limit}
        """)
    List<RemindEntity> findPendingBefore(@Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    /**
     * 多账号：查询指定 client 下的到期提醒。
     */
    @Select("""
        <script>
        SELECT * FROM remind
        WHERE status = 'PENDING'
          <if test="wechatClientId != null">AND wechat_client_id = #{wechatClientId}</if>
          AND remind_at &lt;= #{now}
        ORDER BY remind_at ASC
        LIMIT #{limit}
        </script>
        """)
    List<RemindEntity> findPendingByClient(@Param("wechatClientId") String wechatClientId,
                                            @Param("now") LocalDateTime now,
                                            @Param("limit") int limit);

    @Update("UPDATE remind SET status = #{status} WHERE remind_id = #{remindId}")
    int updateStatus(@Param("remindId") String remindId,
                     @Param("status") String status);

    /**
     * 更新提醒的下一次触发时间，用于周期提醒。
     */
    @Update("""
        UPDATE remind
        SET remind_at = #{nextTime},
            status = 'PENDING',
            repeat_count = IFNULL(repeat_count, 0) + 1,
            updated_at = NOW()
        WHERE remind_id = #{remindId}
        """)
    int updateForNextRepeat(@Param("remindId") String remindId,
                            @Param("nextTime") LocalDateTime nextTime);

    /**
     * 查询所有活跃的周期提醒。
     */
    @Select("""
        SELECT * FROM remind
        WHERE status = 'PENDING'
          AND repeat_type != 'NONE'
          AND (repeat_end_at IS NULL OR remind_at < repeat_end_at)
        ORDER BY remind_at ASC
        LIMIT #{limit}
        """)
    List<RemindEntity> findActiveRecurring(@Param("limit") int limit);
}
