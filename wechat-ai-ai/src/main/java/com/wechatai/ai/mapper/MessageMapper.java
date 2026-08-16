package com.wechatai.ai.mapper;

import com.wechatai.ai.entity.MessageEntity;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息表 MyBatis Mapper — 持久化对话消息。
 */
@Mapper

@Repository("aiMessageMapper")
public interface MessageMapper {

    @Insert("""
        INSERT INTO message (message_id, session_id, role, message_type, content,
                            file_ids, tool_calls, finish_reason, latency_ms, created_at)
        VALUES (#{messageId}, #{sessionId}, #{role}, #{messageType}, #{content},
                #{fileIds}, #{toolCalls}, #{finishReason}, #{latencyMs}, NOW())
    """)
    void insert(MessageEntity msg);

    @Select("""
        SELECT id, message_id, session_id, role, message_type, content,
               file_ids, tool_calls, finish_reason, latency_ms, created_at
        FROM message
        WHERE session_id = #{sessionId}
        ORDER BY created_at ASC
    """)
    @Results({
            @Result(property = "messageId", column = "message_id"),
            @Result(property = "sessionId", column = "session_id"),
            @Result(property = "messageType", column = "message_type"),
            @Result(property = "fileIds", column = "file_ids"),
            @Result(property = "toolCalls", column = "tool_calls"),
            @Result(property = "finishReason", column = "finish_reason"),
            @Result(property = "latencyMs", column = "latency_ms"),
            @Result(property = "createdAt", column = "created_at"),
    })
    List<MessageEntity> findBySessionId(String sessionId);

    @Delete("DELETE FROM message WHERE session_id = #{sessionId}")
    void deleteBySessionId(String sessionId);
}
