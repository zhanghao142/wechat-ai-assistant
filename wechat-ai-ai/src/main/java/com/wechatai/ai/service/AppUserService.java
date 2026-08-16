package com.wechatai.ai.service;

import com.wechatai.ai.entity.AppUserEntity;
import com.wechatai.ai.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 桌面端用户：按邮箱查找或创建，返回稳定 UUID {@code userId}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserService {

    private final AppUserMapper appUserMapper;

    /**
     * 按邮箱查找；不存在则创建新用户。
     *
     * @param email 已规范化的邮箱（小写 trim）
     */
    public AppUserEntity findOrCreateByEmail(String email) {
        AppUserEntity existing = appUserMapper.findByEmail(email);
        if (existing != null) {
            return existing;
        }

        AppUserEntity created = new AppUserEntity();
        created.setUserId(UUID.randomUUID().toString());
        created.setEmail(email);
        try {
            appUserMapper.insert(created);
            log.info("[app_user] 新用户 email={} userId={}", email, created.getUserId());
            return created;
        } catch (DuplicateKeyException e) {
            // 并发注册：以库中已有行为准
            AppUserEntity again = appUserMapper.findByEmail(email);
            if (again != null) {
                return again;
            }
            throw e;
        }
    }
}
