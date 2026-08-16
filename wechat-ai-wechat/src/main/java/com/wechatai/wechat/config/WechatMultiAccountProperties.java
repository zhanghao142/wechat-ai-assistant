package com.wechatai.wechat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信多账号配置属性。
 * <p>
 * 所有配置项均有默认值，未显式配置时不影响单账号模式运行。
 * <p>
 * 配置前缀：{@code wechat}
 */
@Data
@ConfigurationProperties(prefix = "wechat")
public class WechatMultiAccountProperties {

    private MultiAccount multiAccount = new MultiAccount();
    private Client client = new Client();
    private Login login = new Login();
    private Conversation conversation = new Conversation();
    private Inbound inbound = new Inbound();

    @Data
    public static class MultiAccount {
        /** 是否启用多账号模式，默认 false（向后兼容单账号） */
        private boolean enabled = false;
        /** 启动时自动创建的 client 数量 */
        private int initialClients = 0;
    }

    @Data
    public static class Client {
        /** 最大 client 数量 */
        private int maxCount = 20;
        /** 心跳间隔（毫秒） */
        private int heartbeatIntervalMs = 3000;
        /** SDK 读取超时（毫秒） */
        private int readTimeoutMs = 35000;
        /** 连续心跳失败多少次后触发重建 */
        private int rebuildAfterFailures = 10;
        /** 心跳失败持续时间超过此值触发重建（毫秒） */
        private long rebuildAfterDurationMs = 300_000;
    }

    @Data
    public static class Login {
        /** 多账号登录状态文件存储目录 */
        private String storeDirectory = "./wechat-login/clients";
    }

    @Data
    public static class Conversation {
        /** 用户执行器空闲超时（毫秒），超时后自动回收线程 */
        private long executorIdleTimeoutMs = 1_800_000; // 30 分钟
    }

    @Data
    public static class Inbound {
        /** 消息幂等缓存 TTL（分钟） */
        private int idempotencyTtlMinutes = 60;
        /** 消息幂等缓存最大条目数 */
        private int idempotencyMaxSize = 100_000;
    }
}
