package com.wechatai.config;

import org.springframework.context.annotation.Configuration;

/**
 * 异步线程池配置。
 * <p>
 * 供微信回调异步处理、文档解析 Worker、非阻塞耗时任务使用，
 * 避免占用 HTTP 请求线程。
 */
@Configuration
public class ThreadPoolConfig {
    // TODO: 定义 TaskExecutor Bean
}
