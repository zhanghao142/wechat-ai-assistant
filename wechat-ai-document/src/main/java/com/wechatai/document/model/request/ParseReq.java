package com.wechatai.document.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起文档解析请求体：指定已上传的 fileId，后端入队异步解析。
 */
@Data
public class ParseReq {

    @NotBlank
    private String fileId;
}
