package com.wechatai.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 MySQL {@code app_user}：桌面端邮箱登录用户。
 */
@Data
public class AppUserEntity {

    private String userId;
    private String email;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
