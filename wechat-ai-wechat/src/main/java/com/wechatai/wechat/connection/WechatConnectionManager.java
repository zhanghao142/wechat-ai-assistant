package com.wechatai.wechat.connection;
import com.wechatai.wechat.message.WechatMessageDispatcher;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * iLink SDK 生命周期管理 — 扫码登录 / 恢复登录 + 持有 ILinkClient + SDK heartbeat 消息拉取。
 * <p>
 * 启动时优先从 {@link WechatLoginStore} 恢复已持久化的登录状态，
 * 没有则正常扫码登录。心跳成功后自动刷新持久化文件。
 * <p>
 * <b>自动重登录</b>：连续 3 次 heartbeat 返回 "session expired" 时，
 * 自动删除过期登录态文件并重新生成二维码，无需手动重启。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "false", matchIfMissing = true)
public class WechatConnectionManager {

    private final WechatMessageDispatcher dispatcher;
    private final WechatLoginStore loginStore;
    private volatile ILinkClient client;

    /** 连续 session expired 计数 */
    private int sessionExpiredCount = 0;
    private static final int SESSION_EXPIRED_THRESHOLD = 3;
    /** 防止并发重连 */
    private volatile boolean reconnecting = false;

    public WechatConnectionManager(WechatMessageDispatcher dispatcher,
                                    WechatLoginStore loginStore) {
        this.dispatcher = dispatcher;
        this.loginStore = loginStore;
    }

    /** 返回底层 ILinkClient（供发送消息、下载文件等使用）。 */
    public ILinkClient getClient() {
        return client;
    }

    @PostConstruct
    public void init() {
        new Thread(() -> {
            try {
                // ① 尝试从文件恢复登录状态
                ResumeContext resumeCtx = loginStore.load();

                var builder = ILinkClient.builder()
                        .config(ILinkConfig.builder()
                                .readTimeoutMs(35000)
                                .heartbeatEnabled(true)
                                .heartbeatIntervalMs(3000)
                                .build())
                        .onMessage(messages -> {
                            log.debug("[微信轮询] 收到 {} 条消息", messages.size());
                            dispatcher.dispatchMessages(messages);
                        })
                        .onHeartbeat(new OnHeartbeatListener() {
                            @Override
                            public void onHeartbeatSuccess() {
                                sessionExpiredCount = 0;
                                loginStore.save(client);
                            }

                            @Override
                            public void onHeartbeatFailure(Throwable throwable) {
                                handleHeartbeatFailure(throwable);
                            }
                        });

                if (resumeCtx != null) {
                    // ② 恢复模式：跳过扫码，直接用持久化的上下文构建 client
                    client = builder.resumeContext(resumeCtx).build();
                    log.info("✅ 微信登录状态恢复成功（跳过扫码）");
                    loginStore.save(client);
                } else {
                    // ③ 首次登录 / 文件失效：正常扫码
                    client = builder.build();
                    doLogin();
                }

            } catch (Exception e) {
                log.error("微信 SDK 初始化失败", e);
            }
        }, "wechat-ilink-init").start();
    }

    // ================================================================
    // 内部方法
    // ================================================================

    /** 扫码 → 等待确认 → 保存状态。调用前 client 必须已通过 builder.build() 赋值。 */
    private void doLogin() {
        try {
            String qrcodeUrl = client.executeLogin();
            log.info("=== 请扫码登录微信 ===");
            log.info("二维码链接: {}", qrcodeUrl);
            client.getLoginFuture().get();
            log.info("✅ 微信登录成功");
            loginStore.save(client);
            sessionExpiredCount = 0;
        } catch (Exception e) {
            log.error("微信登录失败", e);
        }
    }

    /** 心跳失败回调处理。连续 session expired 达到阈值则触发自动重登录。 */
    private void handleHeartbeatFailure(Throwable throwable) {
        String msg = throwable.getMessage();
        log.warn("微信 heartbeat 拉取失败: {}", msg);

        if (msg != null && msg.contains("session expired")) {
            sessionExpiredCount++;
            if (sessionExpiredCount >= SESSION_EXPIRED_THRESHOLD && !reconnecting) {
                log.warn("⚠️ 连续 {} 次 session expired，触发自动重登录", sessionExpiredCount);
                triggerReconnect();
            }
        }
    }

    /** 异步重连：删文件 → 重新扫码登录。 */
    private synchronized void triggerReconnect() {
        if (reconnecting) return;
        reconnecting = true;
        sessionExpiredCount = 0;

        new Thread(() -> {
            try {
                loginStore.delete();
                log.info("🔄 已删除过期登录态，开始重新登录...");

                // 重建 builder（不复用，避免类型问题）
                var builder = ILinkClient.builder()
                        .config(ILinkConfig.builder()
                                .readTimeoutMs(35000)
                                .heartbeatEnabled(true)
                                .heartbeatIntervalMs(3000)
                                .build())
                        .onMessage(messages -> {
                            log.debug("[微信轮询] 收到 {} 条消息", messages.size());
                            dispatcher.dispatchMessages(messages);
                        })
                        .onHeartbeat(new OnHeartbeatListener() {
                            @Override
                            public void onHeartbeatSuccess() {
                                sessionExpiredCount = 0;
                                loginStore.save(client);
                            }

                            @Override
                            public void onHeartbeatFailure(Throwable throwable) {
                                handleHeartbeatFailure(throwable);
                            }
                        });

                client = builder.build();
                doLogin();
            } catch (Exception e) {
                log.error("自动重登录失败", e);
            } finally {
                reconnecting = false;
            }
        }, "wechat-ilink-reconnect").start();
    }

}
