package com.wechatai.session.model.request;

import com.wechatai.common.enums.SessionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建会话请求体。
 * <p>
 * 第一期不对外暴露 POST /session/create；会话由
 * getOrCreateActiveSession 内部自动创建。本类保留供内部或二期 API 使用。
 */
@Data
public class CreateSessionReq {

    @NotBlank
    private String userId;

    @NotNull
    private SessionSource source;

    /** 可选标题，不传可由系统按首条消息生成 */
    private String title;
}
