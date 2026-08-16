package com.wechatai.session.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新会话标题请求体（文档 3.4）。
 */
@Data
public class UpdateTitleReq {

    @NotBlank
    private String title;
}
