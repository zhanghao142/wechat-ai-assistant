package com.wechatai.tool.model.request;

import lombok.Data;

/**
 * 启停工具请求体：enabled=true 启用，false 禁用。
 */
@Data
public class ToggleToolReq {

    private boolean enabled;
}
