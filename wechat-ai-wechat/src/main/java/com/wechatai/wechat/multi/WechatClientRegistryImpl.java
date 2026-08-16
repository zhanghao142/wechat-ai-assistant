package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.wechatai.common.enums.ClientStatus;
import com.wechatai.wechat.config.WechatMultiAccountProperties;
import com.wechatai.wechat.event.WechatInboundBatchEvent;
import com.wechatai.wechat.exception.ClientLimitReachedException;
import com.wechatai.wechat.exception.ClientNotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link WechatClientRegistry} 的多账号实现。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>管理多个 {@link ManagedWechatClient}（ConcurrentHashMap）</li>
 *   <li>每个 client 独立登录、独立心跳、独立消息路由</li>
 *   <li>启动时按 {@code initial-clients} 自动创建并启动 client</li>
 *   <li>心跳失败超限自动触发重建（删文件 + 重新扫码）</li>
 *   <li>定时清理长期僵尸状态的 client</li>
 *   <li>消息到达时通过 Spring Event 发布 {@link WechatInboundBatchEvent}</li>
 * </ul>
 */
@Slf4j
public class WechatClientRegistryImpl implements WechatClientRegistry {

    private final WechatMultiAccountProperties props;
    private final WechatLoginStateStore loginStateStore;
    private ApplicationEventPublisher eventPublisher;

    /** clientId → ManagedWechatClient */
    private final ConcurrentHashMap<String, ManagedWechatClient> clients = new ConcurrentHashMap<>();

    /** 自增序号，用于生成默认 clientId */
    private final AtomicInteger clientSeq = new AtomicInteger(1);

    public WechatClientRegistryImpl(WechatMultiAccountProperties props, WechatLoginStateStore loginStateStore) {
        this.props = props;
        this.loginStateStore = loginStateStore;
    }

    /**
     * 设置 ApplicationEventPublisher（由 Spring 容器注入后调用）。
     * 不能用构造器注入，因为此 Bean 由 {@code WechatMultiAccountConfig} 手动创建。
     */
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    @PostConstruct
    public void startup() {
        int initialCount = props.getMultiAccount().getInitialClients();
        if (initialCount <= 0) {
            log.info("[多账号] initial-clients=0，启动时不创建任何 client");
            return;
        }

        // 先恢复已持久化的 client
        List<String> stored = loginStateStore.listStoredClients();
        log.info("[多账号] 存储目录中已有 {} 个登录态: {}", stored.size(), stored);

        for (String clientId : stored) {
            createClient(clientId);
        }

        // 不足 initial-clients 则创建新 client
        int toCreate = initialCount - clients.size();
        for (int i = 0; i < toCreate; i++) {
            String clientId = generateClientId();
            createClient(clientId);
        }

        log.info("[多账号] 启动完成，当前 {} 个 client: {}", clients.size(), clients.keySet());
    }

    // ========================================================================
    // 公开接口
    // ========================================================================

    @Override
    public String register(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            clientId = generateClientId();
        }
        if (clients.size() >= props.getClient().getMaxCount()) {
            throw new ClientLimitReachedException(clients.size(), props.getClient().getMaxCount());
        }
        if (clients.containsKey(clientId)) {
            log.warn("[多账号] clientId={} 已存在，返回已有二维码", clientId);
            return clients.get(clientId).getLastQrcodeUrl();
        }
        return createClient(clientId);
    }

    @Override
    public ILinkClient getClient(String clientId) {
        ManagedWechatClient mc = clients.get(clientId);
        if (mc == null) throw new ClientNotFoundException(clientId);
        return mc.client;
    }

    @Override
    public ClientStatus getStatus(String clientId) {
        ManagedWechatClient mc = clients.get(clientId);
        return mc != null ? mc.status : null;
    }

    @Override
    public List<String> listClientIds() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public void remove(String clientId) {
        ManagedWechatClient mc = clients.remove(clientId);
        if (mc != null) {
            mc.status = ClientStatus.CLOSED;
            loginStateStore.delete(clientId);
            log.info("[多账号] client={} 已移除", clientId);
        }
    }

    @Override
    public int count() {
        return clients.size();
    }

    @Override
    public String relogin(String clientId) {
        ManagedWechatClient mc = clients.get(clientId);
        if (mc == null) throw new ClientNotFoundException(clientId);
        loginStateStore.delete(clientId);
        return doLogin(mc);
    }

    @Override
    public String getQrcodeUrl(String clientId) {
        ManagedWechatClient mc = clients.get(clientId);
        return mc != null ? mc.getLastQrcodeUrl() : null;
    }

    /** userId → clientId 映射，用于回复时路由到正确的 Bot */
    private final ConcurrentHashMap<String, String> userClientMap = new ConcurrentHashMap<>();

    @Override
    public void recordUserClient(String userId, String clientId) {
        userClientMap.put(userId, clientId);
    }

    @Override
    public String findClientForUser(String userId) {
        return userClientMap.get(userId);
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /** 生成唯一 clientId */
    private String generateClientId() {
        return "bot-" + clientSeq.getAndIncrement();
    }

    /** 创建并启动一个 client */
    private String createClient(String clientId) {
        ManagedWechatClient mc = new ManagedWechatClient(clientId);
        clients.put(clientId, mc);

        // 尝试恢复登录态
        ResumeContext resumeCtx = loginStateStore.load(clientId);

        ILinkConfig config = ILinkConfig.builder()
                .readTimeoutMs(props.getClient().getReadTimeoutMs())
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(props.getClient().getHeartbeatIntervalMs())
                .build();

        var builder = ILinkClient.builder()
                .config(config)
                .onMessage(messages -> {
                    if (eventPublisher != null && !messages.isEmpty()) {
                        eventPublisher.publishEvent(new WechatInboundBatchEvent(this, clientId, messages));
                    }
                })
                .onHeartbeat(new ClientHeartbeatListener(mc));

        if (resumeCtx != null) {
            mc.client = builder.resumeContext(resumeCtx).build();
            mc.status = ClientStatus.RUNNING;
            log.info("[多账号] client={} 登录状态恢复成功（跳过扫码）", clientId);
            loginStateStore.save(clientId, mc.client);
            return null; // 无需扫码
        }

        // 首次登录
        mc.client = builder.build();
        return doLogin(mc);
    }

    /** 执行扫码登录流程 */
    private String doLogin(ManagedWechatClient mc) {
        try {
            mc.status = ClientStatus.WAITING_FOR_SCAN;
            String qrcodeUrl = mc.client.executeLogin();
            mc.lastQrcodeUrl = qrcodeUrl;
            log.info("[多账号] client={} 请扫码登录\n二维码: {}", mc.clientId, qrcodeUrl);

            // 异步等待登录完成
            new Thread(() -> {
                try {
                    mc.client.getLoginFuture().get();
                    mc.status = ClientStatus.RUNNING;
                    mc.heartbeatFailures.set(0);
                    loginStateStore.save(mc.clientId, mc.client);
                    log.info("[多账号] client={} 登录成功", mc.clientId);
                } catch (Exception e) {
                    mc.status = ClientStatus.NEEDS_RELOGIN;
                    log.error("[多账号] client={} 登录失败", mc.clientId, e);
                }
            }, "wechat-login-" + mc.clientId).start();

            return qrcodeUrl;
        } catch (Exception e) {
            mc.status = ClientStatus.NEEDS_RELOGIN;
            log.error("[多账号] client={} 启动登录流程失败", mc.clientId, e);
            return null;
        }
    }

    /** 定时清理：每 10 分钟移除长期处于 NEEDS_RELOGIN 或 CLOSED 状态的 client */
    @Scheduled(fixedRate = 600_000)
    public void cleanupStaleClients() {
        long threshold = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        Iterator<Map.Entry<String, ManagedWechatClient>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ManagedWechatClient> entry = it.next();
            ManagedWechatClient mc = entry.getValue();
            if ((mc.status == ClientStatus.CLOSED || mc.status == ClientStatus.NEEDS_RELOGIN)
                    && mc.createdAt < threshold) {
                it.remove();
                loginStateStore.delete(entry.getKey());
                log.info("[多账号] 清理过期 client: {}", entry.getKey());
            }
        }
    }

    // ========================================================================
    // 内部类：ManagedWechatClient
    // ========================================================================

    static class ManagedWechatClient {
        final String clientId;
        volatile ILinkClient client;
        volatile ClientStatus status = ClientStatus.CREATED;
        final AtomicInteger heartbeatFailures = new AtomicInteger(0);
        volatile long firstFailureAt = 0;
        final long createdAt = System.currentTimeMillis();
        volatile String lastQrcodeUrl;

        ManagedWechatClient(String clientId) {
            this.clientId = clientId;
        }

        String getLastQrcodeUrl() {
            return lastQrcodeUrl;
        }
    }

    // ========================================================================
    // 内部类：心跳监听器
    // ========================================================================

    class ClientHeartbeatListener implements OnHeartbeatListener {
        private final ManagedWechatClient mc;
        private final int rebuildThreshold;
        private final long rebuildDurationMs;

        ClientHeartbeatListener(ManagedWechatClient mc) {
            this.mc = mc;
            this.rebuildThreshold = props.getClient().getRebuildAfterFailures();
            this.rebuildDurationMs = props.getClient().getRebuildAfterDurationMs();
        }

        @Override
        public void onHeartbeatSuccess() {
            mc.heartbeatFailures.set(0);
            mc.firstFailureAt = 0;
            if (mc.status != ClientStatus.RUNNING) {
                mc.status = ClientStatus.RUNNING;
            }
            loginStateStore.save(mc.clientId, mc.client);
        }

        @Override
        public void onHeartbeatFailure(Throwable throwable) {
            int failures = mc.heartbeatFailures.incrementAndGet();
            long now = System.currentTimeMillis();

            if (mc.firstFailureAt == 0) {
                mc.firstFailureAt = now;
            }

            String msg = throwable != null ? throwable.getMessage() : "unknown";
            log.warn("[多账号] client={} 心跳失败 #{}, msg={}", mc.clientId, failures, msg);

            // 检查是否触发重建：连续失败次数超限 或 失败持续时间超限
            boolean countExceeded = failures >= rebuildThreshold;
            boolean durationExceeded = (now - mc.firstFailureAt) >= rebuildDurationMs;

            if ((countExceeded || durationExceeded) && mc.status != ClientStatus.NEEDS_RELOGIN) {
                log.warn("[多账号] client={} 触发重建 (failures={}, duration={}ms)",
                        mc.clientId, failures, now - mc.firstFailureAt);
                triggerRebuild();
            }
        }

        private void triggerRebuild() {
            mc.status = ClientStatus.NEEDS_RELOGIN;
            mc.heartbeatFailures.set(0);
            mc.firstFailureAt = 0;
            loginStateStore.delete(mc.clientId);

            new Thread(() -> {
                try {
                    log.info("[多账号] client={} 开始重建...", mc.clientId);
                    var builder = ILinkClient.builder()
                            .config(ILinkConfig.builder()
                                    .readTimeoutMs(props.getClient().getReadTimeoutMs())
                                    .heartbeatEnabled(true)
                                    .heartbeatIntervalMs(props.getClient().getHeartbeatIntervalMs())
                                    .build())
                            .onMessage(messages -> {
                                if (eventPublisher != null && !messages.isEmpty()) {
                                    eventPublisher.publishEvent(
                                            new WechatInboundBatchEvent(WechatClientRegistryImpl.this, mc.clientId, messages));
                                }
                            })
                            .onHeartbeat(new ClientHeartbeatListener(mc));

                    mc.client = builder.build();
                    doLogin(mc);
                } catch (Exception e) {
                    log.error("[多账号] client={} 重建失败", mc.clientId, e);
                }
            }, "wechat-rebuild-" + mc.clientId).start();
        }
    }
}
