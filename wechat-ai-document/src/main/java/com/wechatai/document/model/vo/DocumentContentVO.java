package com.wechatai.document.model.vo;

import lombok.Data;

/**
 * 已解析文档内容视图，供 AI 检索或调试接口返回纯文本。
 */
@Data
public class DocumentContentVO {

    private String fileId;
    private String fileName;
    /** 解析后的全文 */
    private String content;
    /** 内容类型提示，如 text/plain */
    private String contentType;
}
