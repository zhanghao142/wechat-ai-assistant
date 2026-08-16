package com.wechatai.session.mapper;

import com.wechatai.session.entity.DesktopNotificationEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DesktopNotificationMapper {

    @Insert("""
        INSERT INTO desktop_notification
          (notification_id, user_id, session_id, remind_id, content, created_at)
        VALUES
          (#{notificationId}, #{userId}, #{sessionId}, #{remindId}, #{content}, NOW())
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DesktopNotificationEntity entity);

    @Select("""
        SELECT * FROM desktop_notification
        WHERE user_id = #{userId}
          AND read_at IS NULL
        ORDER BY created_at ASC
        LIMIT #{limit}
        """)
    List<DesktopNotificationEntity> findUnreadByUser(@Param("userId") String userId,
                                                     @Param("limit") int limit);

    @Update("""
        UPDATE desktop_notification
        SET read_at = NOW()
        WHERE notification_id = #{notificationId}
          AND user_id = #{userId}
          AND read_at IS NULL
        """)
    int markRead(@Param("notificationId") String notificationId,
                 @Param("userId") String userId);

    @Update("""
        UPDATE desktop_notification
        SET read_at = NOW()
        WHERE user_id = #{userId}
          AND read_at IS NULL
        """)
    int markAllRead(@Param("userId") String userId);
}
