package com.wechatai.proactive.path;

import com.wechatai.proactive.model.BehaviorPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路径维护定时任务。
 * <p>
 * 每天凌晨 3 点归档过期路径，凌晨 4 点检测矛盾路径。
 */
@Component
public class PathMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(PathMaintenanceScheduler.class);

    private final BehaviorPathMapper mapper;
    private final PathEmbeddingService embeddingService;

    public PathMaintenanceScheduler(BehaviorPathMapper mapper, PathEmbeddingService embeddingService) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
    }

    /** 每天凌晨 3 点：归档过期路径 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void archiveExpired() {
        log.info("【路径归档】开始扫描...");
        try {
            List<BehaviorPath> expired = mapper.findExpired();
            if (expired.isEmpty()) {
                log.info("【路径归档】无过期路径");
                return;
            }

            int count = 0;
            for (BehaviorPath p : expired) {
                mapper.archive(p.getPathId());
                embeddingService.deletePath(p.getPathId());
                count++;
            }
            log.info("✅ 【路径归档】完成，归档 {} 条路径", count);
        } catch (Exception e) {
            log.error("【路径归档】异常: {}", e.getMessage(), e);
        }
    }

    /** 每天凌晨 4 点：检测矛盾路径 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void detectConflicts() {
        log.info("【冲突检测】开始扫描...");
        try {
            List<BehaviorPath> conflicts = mapper.findConflicting();
            if (conflicts.isEmpty()) {
                log.info("【冲突检测】无矛盾路径");
                return;
            }

            int count = 0;
            for (BehaviorPath p : conflicts) {
                mapper.markDegraded(p.getPathId());
                log.warn("【冲突检测】路径 {} 标记为 degraded（成功{}次/失败{}次 conf={}）",
                        p.getPathId(), p.getSuccessCount(), p.getFailCount(),
                        String.format("%.2f", p.getConfidence()));
                count++;
            }
            log.info("✅ 【冲突检测】完成，标记 {} 条矛盾路径", count);
        } catch (Exception e) {
            log.error("【冲突检测】异常: {}", e.getMessage(), e);
        }
    }
}
