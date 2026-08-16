package com.wechatai.document.mapper;

import com.wechatai.document.entity.DocumentEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DocumentMapper {

    @Insert("""
        INSERT INTO document (file_id, session_id, user_id, file_name,
                              file_type, file_size, file_path, status)
        VALUES (#{fileId}, #{sessionId}, #{userId}, #{fileName},
                #{fileType}, #{fileSize}, #{filePath}, #{status})
    """)
    int insert(DocumentEntity entity);

    @Select("SELECT * FROM document WHERE file_id = #{fileId}")
    DocumentEntity findByFileId(String fileId);

    @Select("""
        <script>
        SELECT * FROM document
        WHERE session_id = #{sessionId}
        <if test="status != null and status != ''">
            AND status = #{status}
        </if>
        ORDER BY uploaded_at DESC
        </script>
    """)
    List<DocumentEntity> findBySessionId(@Param("sessionId") String sessionId,
                                          @Param("status") String status);

    @Update("UPDATE document SET status = #{status} WHERE file_id = #{fileId}")
    int updateStatus(DocumentEntity entity);

    /**
     * 解析成功：更新状态 + 文本内容 + 解析时间
     */
    @Update("UPDATE document SET status = #{status}, content = #{content}, parsed_at = NOW() WHERE file_id = #{fileId}")
    int updateParseResult(@Param("fileId") String fileId,
                          @Param("status") String status,
                          @Param("content") String content);

    /**
     * 解析失败：更新状态 + 错误信息
     */
    @Update("UPDATE document SET status = 'FAILED', error_message = #{errorMsg} WHERE file_id = #{fileId}")
    int updateParseError(@Param("fileId") String fileId,
                         @Param("errorMsg") String errorMsg);

    @Delete("DELETE FROM document WHERE file_id = #{fileId}")
    int deleteByFileId(String fileId);

    /** 统计会话下已解析的文档数量（轻量，不查 content 大字段） */
    @Select("SELECT COUNT(*) FROM document WHERE session_id = #{sessionId} AND status = 'PARSED'")
    int countBySessionId(@Param("sessionId") String sessionId);
}
