package com.wechatai.proactive.path;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.proactive.model.BehaviorPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 路径合并服务 — ApplicationReadyEvent 时全量扫描，合并重复路径。
 * <p>
 * 规则：同一 tool_chain + 向量余弦相似度 > 0.85 → 合并 α/β，
 * 保留创建最早的路径，删除被合并方（MySQL + Qdrant）。
 */
@Service
public class PathMergeService {

    private static final Logger log = LoggerFactory.getLogger(PathMergeService.class);
    private static final double MERGE_THRESHOLD = 0.85;

    private final BehaviorPathMapper mapper;
    private final PathEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public PathMergeService(BehaviorPathMapper mapper, PathEmbeddingService embeddingService) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void mergeOnStartup() {
        log.info("【路径合并】开始全量扫描...");
        int merged = 0;

        try {
            List<BehaviorPath> all = mapper.findAllActive();
            if (all.size() < 2) {
                log.info("【路径合并】路径数不足2条，跳过");
                return;
            }

            // 按 tool_chain 分组（同一工具链才有合并意义）
            Map<String, List<BehaviorPath>> byToolChain = all.stream()
                    .filter(p -> p.getToolChain() != null && !p.getToolChain().isBlank())
                    .collect(Collectors.groupingBy(BehaviorPath::getToolChain));

            for (var entry : byToolChain.entrySet()) {
                List<BehaviorPath> group = entry.getValue();
                if (group.size() < 2) continue;

                // 组内两两比较
                Set<String> removed = new HashSet<>();
                for (int i = 0; i < group.size(); i++) {
                    if (removed.contains(group.get(i).getPathId())) continue;
                    for (int j = i + 1; j < group.size(); j++) {
                        if (removed.contains(group.get(j).getPathId())) continue;

                        BehaviorPath a = group.get(i);
                        BehaviorPath b = group.get(j);
                        double sim = cosineSim(a.getTriggerEmbedding(), b.getTriggerEmbedding());

                        if (sim > MERGE_THRESHOLD) {
                            // 保留创建早的，合并创建晚的
                            BehaviorPath keeper, removed2;
                            if (a.getCreatedAt().isBefore(b.getCreatedAt())) {
                                keeper = a; removed2 = b;
                            } else {
                                keeper = b; removed2 = a;
                            }

                            // 合并统计
                            mapper.mergeInto(keeper.getPathId(), removed2.getAlpha(),
                                    removed2.getBeta(), removed2.getUseCount(),
                                    removed2.getSuccessCount(), removed2.getFailCount());

                            // 删除被合并方
                            mapper.archive(removed2.getPathId());
                            embeddingService.deletePath(removed2.getPathId());
                            removed.add(removed2.getPathId());
                            merged++;

                            log.info("【路径合并】{} + {} → {} (sim={})",
                                    shortId(removed2.getPathId()),
                                    shortId(keeper.getPathId()),
                                    shortId(keeper.getPathId()),
                                    String.format("%.2f", sim));
                        }
                    }
                }
            }

            log.info("✅ 【路径合并】完成，合并 {} 条路径", merged);

        } catch (Exception e) {
            log.error("【路径合并】异常: {}", e.getMessage(), e);
        }
    }

    private static String shortId(String id) {
        if (id == null) return "";
        return id.length() <= 18 ? id : id.substring(0, 18);
    }

    /** 解析 JSON 数组 embedding → List<Float> → 计算余弦相似度 */
    private double cosineSim(String embA, String embB) {
        if (embA == null || embB == null || embA.isBlank() || embB.isBlank()) return 0;
        try {
            List<Float> a = objectMapper.readValue(embA, new TypeReference<List<Float>>() {});
            List<Float> b = objectMapper.readValue(embB, new TypeReference<List<Float>>() {});
            if (a.isEmpty() || b.isEmpty() || a.size() != b.size()) return 0;

            double dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.size(); i++) {
                double va = a.get(i);
                double vb = b.get(i);
                dot += va * vb;
                normA += va * va;
                normB += vb * vb;
            }
            return dot / (Math.sqrt(normA) * Math.sqrt(normB));
        } catch (Exception e) {
            return 0;
        }
    }
}
