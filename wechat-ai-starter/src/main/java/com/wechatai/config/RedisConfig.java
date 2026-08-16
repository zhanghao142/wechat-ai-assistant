package com.wechatai.config;

import org.springframework.context.annotation.Configuration;

/**
 * Redis 全局配置。
 * <p>
 * 配置 RedisTemplate / StringRedisTemplate 的序列化等，
 * 支撑缓存、任务队列与限流（见 RedisKeys）。
 */
@Configuration
public class RedisConfig {
    // TODO: RedisTemplate / StringRedisTemplate 序列化配置
}
