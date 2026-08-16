package com.wechatai.session.mapper;

import com.wechatai.session.entity.MessageEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 消息表 MyBatis Mapper。
 * <p>
 * 配合 SessionPrepareNode 实现对话历史的 MySQL 持久化，
 * 替代原有的 ConcurrentHashMap 内存方案。
 */
@Mapper
public interface MessageMapper {

    /**
     * 插入一条消息。
     */
    @Insert("""
        INSERT INTO message (message_id, session_id, role, message_type,
                             content, file_ids, tool_calls, finish_reason,
                             latency_ms, created_at)
        VALUES (#{messageId}, #{sessionId}, #{role}, #{messageType},
                #{content}, #{fileIds}, #{toolCalls}, #{finishReason},
                #{latencyMs}, NOW())
    """)
    int insert(MessageEntity entity);

    /**
     * 查询某会话最近 N 条消息（按 created_at 倒序取 N 条）。
     *
     * @param sessionId 会话 ID
     * @param limit     返回条数上限
     * @return 最近 N 条消息（倒序），调用方自行反转
     */
    @Select("""
        SELECT * FROM message
        WHERE session_id = #{sessionId}
        ORDER BY id DESC
        LIMIT #{limit}
    """)
    List<MessageEntity> findLatestBySessionId(@Param("sessionId") String sessionId,
                                              @Param("limit") int limit);

    /**
     * 按 message_id 更新消息内容。
     * 主要用于工具调用回填（tool_calls、finish_reason 等）。
     */
    @Update("""
        UPDATE message SET
            tool_calls = #{toolCalls},
            finish_reason = #{finishReason},
            latency_ms = #{latencyMs}
        WHERE message_id = #{messageId}
    """)
    int updateByMessageId(MessageEntity entity);

    /**
     * 按关键词搜索当前 session 的历史消息（不含 SYSTEM）。
     * 用于 searchConversation 工具翻聊天记录。
     */
    @Select("""
        SELECT * FROM message
        WHERE session_id = #{sessionId}
          AND role != 'SYSTEM'
          AND content LIKE CONCAT('%', #{keyword}, '%')
        ORDER BY id DESC
        LIMIT #{limit}
    """)
    List<MessageEntity> searchByKeyword(@Param("sessionId") String sessionId,
                                        @Param("keyword") String keyword,
                                        @Param("limit") int limit);

    /**
     * 按 id 范围查询消息，用于获取某条消息前后的上下文。
     */
    @Select("""
        SELECT * FROM message
        WHERE id BETWEEN #{startId} AND #{endId}
          AND session_id = #{sessionId}
        ORDER BY id ASC
    """)
    List<MessageEntity> findRangeById(@Param("startId") long startId,
                                      @Param("endId") long endId,
                                      @Param("sessionId") String sessionId);
}
