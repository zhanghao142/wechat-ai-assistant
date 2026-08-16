package com.wechatai.document.entity;

import com.wechatai.common.enums.FileStatus;
import com.wechatai.common.enums.FileType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 MySQL 文档表的实体。
 * <p>
 * {@link #content} 为解析后的纯文本（LONGTEXT），供 AI 检索；
 * 原文件路径在 {@link #filePath}。状态流转见 {@link FileStatus}。
 */
@Data
public class DocumentEntity {

    /** 自增主键 */
    private Long id;
    /** 业务文件 ID */
    private String fileId;
    /** 所属会话 */
    private String sessionId;
    /** 上传用户 */
    private String userId;
    /** 原始文件名 */
    private String fileName;
    /** 文件类型 PDF/DOC/DOCX/TXT */
    private FileType fileType;
    /** 字节大小 */
    private Long fileSize;
    /** 本地或 OSS 存储路径 */
    private String filePath;
    /** UPLOADED / PARSING / PARSED / FAILED */
    private FileStatus status;
    /** 解析后的纯文本（LONGTEXT） */
    private String content;
    /** 解析失败时的错误信息 */
    private String errorMessage;
    /** 上传完成时间 */
    private LocalDateTime uploadedAt;
    /** 解析完成时间 */
    private LocalDateTime parsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
