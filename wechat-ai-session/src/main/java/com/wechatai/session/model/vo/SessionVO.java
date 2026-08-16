package com.wechatai.session.model.vo;

import com.wechatai.common.enums.SessionStatus;
import lombok.Data;

/**
 * 会话详情/列表对外展示对象（文档 3.2、3.3 响应 data）。
 * 时间字段统一为 {@code yyyy-MM-dd HH:mm:ss} 字符串。
 */
@Data
public class SessionVO {

    private String sessionId;
    private String userId;
    private String title;
    private SessionStatus status;
    private Integer messageCount;
    private String lastMessageAt;
    private String createdAt;
    private String expireAt;
}
