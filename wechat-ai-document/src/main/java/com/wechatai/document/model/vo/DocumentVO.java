package com.wechatai.document.model.vo;

import com.wechatai.common.enums.FileStatus;
import com.wechatai.common.enums.FileType;
import lombok.Data;

/**
 * 文档元信息展示对象（列表/状态查询），不含全文 content。
 */
@Data
public class DocumentVO {

    private String fileId;
    private String fileName;
    private Long fileSize;
    private FileType fileType;
    private FileStatus status;
    private String uploadedAt;
    private String parsedAt;
}
