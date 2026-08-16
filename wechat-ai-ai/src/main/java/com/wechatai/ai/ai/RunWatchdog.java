package com.wechatai.ai.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * ReAct 执行看门狗 — 给同步阻塞调用加超时壳。
 * <p>
 * 三层超时策略（均可通过 application.properties 配置）：
 * <ol>
 *   <li>工具级（wechat.watchdog.tool-timeout-ms，默认 30s）→ 超时注入 error JSON</li>
 *   <li>Agent 级（wechat.watchdog.agent-timeout-ms，默认 240s）→ 超时返回 fail，中央降级</li>
 *   <li>Bridge 总超时（wechat.watchdog.bridge-timeout-ms，默认 90s）→ 发提示给用户</li>
 * </ol>
 * <p>
 * 纯粹的超时壳：正常完成原样返回，超时抛 TimeoutException，不碰任何业务逻辑。
 */
@Component
public class RunWatchdog {

    private static final Logger log = LoggerFactory.getLogger(RunWatchdog.class);

    /** 工具级超时（毫秒），可通过 wechat.watchdog.tool-timeout-ms 配置 */
    private final long toolTimeoutMs;

    /** 子 Agent 整轮超时（毫秒），深研等多步任务用更长窗口 */
    private final long agentTimeoutMs;

    /** Bridge 总超时（毫秒），可通过 wechat.watchdog.bridge-timeout-ms 配置 */
    private final long bridgeTimeoutMs;

    public RunWatchdog(
            @Value("${wechat.watchdog.tool-timeout-ms:30000}") long toolTimeoutMs,
            @Value("${wechat.watchdog.agent-timeout-ms:240000}") long agentTimeoutMs,
            @Value("${wechat.watchdog.bridge-timeout-ms:90000}") long bridgeTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
        this.agentTimeoutMs = agentTimeoutMs;
        this.bridgeTimeoutMs = bridgeTimeoutMs;
        log.info("✅ Watchdog 初始化：工具超时 {}ms, Agent 超时 {}ms, Bridge 总超时 {}ms",
                toolTimeoutMs, agentTimeoutMs, bridgeTimeoutMs);
    }

    public long getToolTimeoutMs() { return toolTimeoutMs; }
    public long getAgentTimeoutMs() { return agentTimeoutMs; }
    public long getBridgeTimeoutMs() { return bridgeTimeoutMs; }

    /**
     * 专用于超时包装的线程池。
     * Cached 而非 Fixed：这些线程大部分时间在等 Future.get()，不占 CPU。
     */
    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "watchdog");
        t.setDaemon(true);
        return t;
    });

    /**
     * 给有返回值的同步任务加超时壳。
     *
     * @param task      真正执行的逻辑
     * @param timeoutMs 超时毫秒
     * @param label     日志标签
     * @return 任务返回值
     * @throws TimeoutException 超时
     */
    public <T> T runWithTimeout(Callable<T> task, long timeoutMs, String label) throws Exception {
        Future<T> future = timeoutExecutor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("【Watchdog】{} 超时 ({}ms)", label, timeoutMs);
            throw e;
        } catch (ExecutionException e) {
            // 把 ExecutionException 解开，抛出原始异常
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                log.warn("【Watchdog】{} 执行异常: {}", label, ex.getMessage());
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * 给无返回值的同步任务加超时壳。
     */
    public void runWithTimeout(Runnable task, long timeoutMs, String label) throws TimeoutException {
        Future<?> future = timeoutExecutor.submit(task);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("【Watchdog】{} 超时 ({}ms)", label, timeoutMs);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("【Watchdog】{} 执行异常: {}", label,
                    cause != null ? cause.getMessage() : "unknown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【Watchdog】{} 被中断", label);
        }
    }
}
