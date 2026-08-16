package com.wechatai.proactive.path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.proactive.config.ProactiveConfig;
import com.wechatai.proactive.critic.CriticEvaluator;
import com.wechatai.proactive.critic.CriticTriggerDecider;
import com.wechatai.proactive.critic.CriticVerdict;
import com.wechatai.proactive.critic.RuleInjector;
import com.wechatai.proactive.feedback.FeedbackParser;
import com.wechatai.proactive.model.BehaviorPath;
import com.wechatai.proactive.model.DecisionTrace;
import com.wechatai.proactive.model.FeedbackSignal;
import com.wechatai.proactive.model.PathEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行为路径自学习核心服务。
 * <p>
 * 三个入口：
 * <ol>
 *   <li>{@link #retrieveAndDecide(String, String, String)} — Agent 启动前：RAG 检索 → 策略决策</li>
 *   <li>{@link #recordAfterAgentAsync(DecisionTrace, List, String, int, long)} — Agent 完成后：异步录制（不挡回复）</li>
 *   <li>{@link #collectFeedback(String, String)} — 下一条消息：解析反馈 → 更新置信度</li>
 * </ol>
 */
@Service
public class BehaviorPathService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorPathService.class);

    private final BehaviorPathMapper mapper;
    private final PathEmbeddingService embeddingService;
    private final ConfidenceUpdater confidenceUpdater;
    private final FeedbackParser feedbackParser;
    private final ProactiveConfig config;
    private final CriticTriggerDecider criticTrigger;
    private final CriticEvaluator criticEvaluator;
    private final RuleInjector ruleInjector;
    private final ObjectMapper objectMapper;

    /** 待反馈的 trace — userId → traceId */
    private final Map<String, String> pendingFeedback = new LinkedHashMap<>();

    /** 路径录制专用小池：冷启动 embed+索引较慢，勿挡 Agent 返回 */
    private final ExecutorService recordExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "path-record-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public BehaviorPathService(BehaviorPathMapper mapper,
                               PathEmbeddingService embeddingService,
                               ConfidenceUpdater confidenceUpdater,
                               FeedbackParser feedbackParser,
                               ProactiveConfig config,
                               CriticTriggerDecider criticTrigger,
                               CriticEvaluator criticEvaluator,
                               RuleInjector ruleInjector) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.confidenceUpdater = confidenceUpdater;
        this.feedbackParser = feedbackParser;
        this.config = config;
        this.criticTrigger = criticTrigger;
        this.criticEvaluator = criticEvaluator;
        this.ruleInjector = ruleInjector;
        this.objectMapper = new ObjectMapper();
    }

    // ════════════════════════════════════════════════════════════════
    // 入口 1: Agent 启动前 → RAG 检索 + 策略决策
    // ════════════════════════════════════════════════════════════════

    /**
     * 检索相似历史路径，返回策略决策结果。
     *
     * @param userMessage 用户原始消息
     * @param userId      用户标识
     * @param sessionId   会话 ID
     * @return 策略决策（含是否热路径、推荐工具链等）
     */
    public PathDecision retrieveAndDecide(String userMessage, String userId, String sessionId) {
        if (!config.isEnabled() || userMessage == null || userMessage.isBlank()) {
            return PathDecision.coldStart();
        }

        long start = System.currentTimeMillis();

        // 1. Embedding 编码
        List<Float> eventEmbedding;
        try {
            eventEmbedding = embeddingService.embed(userMessage);
        } catch (Exception e) {
            log.warn("【路径检索】Embedding 失败，降级为冷启动: {}", e.getMessage());
            return PathDecision.coldStart();
        }

        // 2. 先创建决策追踪（无论有没有匹配路径，都要记录）
        DecisionTrace trace = DecisionTrace.builder()
                .traceId(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(sessionId)
                .eventType("user_message")
                .eventPayload(toJson(Map.of("message", userMessage)))
                .eventEmbedding(eventEmbedding.toString())
                .createdAt(Instant.now())
                .build();

        // 3. Qdrant 向量检索
        int topK = config.getRetrieveTopK();
        float minScore = (float) config.getMinSimilarity();
        List<PathEmbeddingService.PathMatch> matches =
                embeddingService.search(eventEmbedding, topK, minScore);

        if (matches.isEmpty()) {
            log.info("【路径检索】event={} 无匹配路径 → 冷启动", truncate(userMessage, 60));
            trace.setSelectedStrategy("slow_path");
            trace.setOutcome("partial");
            try { mapper.insertTrace(trace); } catch (Exception e) { log.warn("写入 trace 失败: {}", e.getMessage()); }
            return new PathDecision("slow_path", null, null, trace, null);
        }

        // 4. 从 MySQL 加载完整路径记录
        List<BehaviorPath> ranked = new ArrayList<>();
        for (PathEmbeddingService.PathMatch m : matches) {
            BehaviorPath path = mapper.findByPathId(m.pathId());
            if (path == null) continue;
            path.setSimilarity(m.score());
            path.setRetrievalScore(m.score() * confidenceUpdater.confidence(path));
            ranked.add(path);
        }

        // Qdrant 命中了但 MySQL 查不到对应记录 → 清理脏数据，降级冷启动
        if (ranked.isEmpty()) {
            log.warn("【路径检索】Qdrant 命中 {} 条但 MySQL 均缺失，清理并降级冷启动", matches.size());
            for (PathEmbeddingService.PathMatch m : matches) {
                embeddingService.deletePath(m.pathId());
            }
            trace.setSelectedStrategy("slow_path");
            trace.setOutcome("partial");
            try { mapper.insertTrace(trace); } catch (Exception e) {}
            return new PathDecision("slow_path", null, null, trace, null);
        }

        // 排序前应用时间衰减
        for (BehaviorPath path : ranked) {
            double decay = recencyDecay(path);
            path.setRetrievalScore(path.getRetrievalScore() * decay);
        }

        ranked.sort((a, b) -> Double.compare(b.getRetrievalScore(), a.getRetrievalScore()));

        // 5. 按置信度决策
        BehaviorPath best = ranked.get(0);
        double bestConf = confidenceUpdater.confidence(best);
        String strategy;
        String toolChainJson = null;

        // 个人权重：用户对该路径使用过 3 次以上 → 加权
        double finalConf = bestConf;
        if (userId != null) {
            int userUse = mapper.countUserUsage(best.getPathId(), userId);
            if (userUse >= 3) {
                int userSuccess = mapper.countUserSuccess(best.getPathId(), userId);
                double userConf = (double) userSuccess / userUse;
                finalConf = 0.7 * bestConf + 0.3 * userConf;
                log.info("【个人权重】pathId={} 全局conf={} 用户conf={}→{} 最终conf={}",
                        truncate(best.getPathId(), 18),
                        String.format("%.2f", bestConf), String.format("%.2f", userConf),
                        String.format("%.2f", finalConf));
            }
        }

        // degraded 路径 → 强制温路径（即使全局 conf 高也不走热）
        boolean isDegraded = "degraded".equals(best.getOutcome());

        if (!isDegraded && finalConf >= config.getHotThreshold()) {
            strategy = "fast_path";
            toolChainJson = best.getToolChain();
            log.info("【路径决策】🟢 热路径 pathId={} conf={} sim={}",
                    best.getPathId(), String.format("%.2f", finalConf), String.format("%.2f", best.getSimilarity()));
        } else {
            strategy = "hybrid";
            toolChainJson = best.getToolChain();
            log.info("【路径决策】🟡 温路径 pathId={} conf={} sim={}{}",
                    best.getPathId(), String.format("%.2f", finalConf), String.format("%.2f", best.getSimilarity()),
                    isDegraded ? " (degraded)" : "");
        }

        trace.setMatchedPaths(toJson(ranked.stream().map(p -> Map.of(
                "pathId", p.getPathId(),
                "similarity", p.getSimilarity(),
                "confidence", p.getConfidence()
        )).toList()));
        trace.setSelectedStrategy(strategy);
        trace.setOutcome("partial");

        try {
            mapper.insertTrace(trace);
        } catch (Exception e) {
            log.warn("【路径追踪】写入 trace 失败: {}", e.getMessage());
        }

        return new PathDecision(strategy, toolChainJson, best, trace, best.getCriticRules());
    }

    // ════════════════════════════════════════════════════════════════
    // 入口 2: Agent 完成后 → 录制工具链（异步，不挡用户回复）
    // ════════════════════════════════════════════════════════════════

    /**
     * 异步录制：先同步打上「待反馈」标记，再把 embed/建路径放到后台。
     * 这样用户下一条消息进来时 {@link #collectFeedback} 仍能对上 trace。
     */
    public void recordAfterAgentAsync(DecisionTrace trace,
                                      List<Map<String, Object>> toolCalls,
                                      String agentResponse,
                                      int rounds,
                                      long elapsedMs) {
        if (!config.isEnabled() || trace == null) return;

        // 快照：避免调用方后续改 list
        List<Map<String, Object>> toolsSnapshot = toolCalls == null
                ? List.of()
                : List.copyOf(toolCalls);
        String replySnapshot = agentResponse;

        // 同步：先挂待反馈，避免异步未完成时下一条消息丢反馈
        if (trace.getUserId() != null && !trace.getUserId().isBlank()) {
            pendingFeedback.put(trace.getUserId(), trace.getTraceId());
        }

        recordExecutor.execute(() -> {
            try {
                recordAfterAgent(trace, toolsSnapshot, replySnapshot, rounds, elapsedMs);
            } catch (Exception e) {
                log.error("【路径录制】异步执行失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Agent 执行完成后，录制工具链并入库（同步实现，供异步包装调用）。
     */
    public void recordAfterAgent(DecisionTrace trace,
                                 List<Map<String, Object>> toolCalls,
                                 String agentResponse,
                                 int rounds,
                                 long elapsedMs) {
        if (!config.isEnabled() || trace == null) return;

        try {
            String toolChainJson = toJson(toolCalls);
            trace.setExecutedToolChain(toolChainJson);
            trace.setAgentResponse(agentResponse);
            trace.setLlmCalls(rounds);
            trace.setLatencyMs((int) elapsedMs);

            // 热路径 → 复用成功，更新置信度；冷启动 → 新建路径
            if ("slow_path".equals(trace.getSelectedStrategy()) && toolCalls != null && !toolCalls.isEmpty()) {
                // 冷启动 → 新建路径（含 embed + Qdrant，可能较慢）
                BehaviorPath newPath = createNewPath(trace, toolCalls);
                trace.setNewPathCreated(true);
                trace.setNewPathId(newPath.getPathId());
                trace.setOutcome("partial");
                log.info("【路径录制】冷启动 → 新建路径 pathId={}", newPath.getPathId());
            } else if (toolCalls == null || toolCalls.isEmpty()) {
                // 无工具调用（闲聊）—— 不记录路径
                trace.setOutcome("success");
            } else {
                // 热/温路径 → 待反馈确认
                trace.setOutcome("partial");
            }

            mapper.updateTraceAfterAgent(trace);

            // 再次确保待反馈（异步路径可能已提前 put）
            if ("partial".equals(trace.getOutcome()) && trace.getUserId() != null) {
                pendingFeedback.put(trace.getUserId(), trace.getTraceId());
            }

        } catch (Exception e) {
            log.error("【路径录制】失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        recordExecutor.shutdown();
    }

    // ════════════════════════════════════════════════════════════════
    // 入口 3: 下一条消息 → 解析反馈 + 更新置信度
    // ════════════════════════════════════════════════════════════════

    /**
     * 收集上一条 Agent 回复的用户反馈。
     *
     * @param userId      用户标识
     * @param userMessage 用户回复文本
     */
    public void collectFeedback(String userId, String userMessage) {
        if (!config.isEnabled()) return;

        String traceId = pendingFeedback.remove(userId);
        if (traceId == null) return;

        try {
            DecisionTrace trace = mapper.findByTraceId(traceId);
            if (trace == null) return;

            // 1. 解析反馈信号
            FeedbackSignal signal = feedbackParser.parse(userMessage);
            trace.setUserReaction(userMessage);

            if (signal.isNone()) {
                // 无反馈 → 不更新置信度
                trace.setOutcome("success");
                mapper.updateTraceFeedback(trace);
                return;
            }

            // 2. 找到关联路径
            String pathId = trace.isNewPathCreated() ? trace.getNewPathId() : null;
            if (pathId == null && trace.getMatchedPaths() != null) {
                // 从 matchedPaths 中取最优路径 ID
                pathId = extractBestPathId(trace.getMatchedPaths());
            }
            if (pathId == null) return;

            BehaviorPath path = mapper.findByPathId(pathId);
            if (path == null) return;

            // 3. 更新置信度
            if (signal.isPositive()) {
                confidenceUpdater.update(path, true);
                path.setOutcome("success");
                path.setFeedbackType(signal.getType().name().toLowerCase());
                path.setUserFeedback(userMessage);
                log.info("【置信度】pathId={} 正面反馈 conf={}", pathId, path.getConfidence());
            } else if (signal.isNegative()) {
                confidenceUpdater.update(path, false);
                path.setOutcome("failure");
                path.setFeedbackType(signal.getType().name().toLowerCase());
                path.setUserFeedback(userMessage);
                log.info("【置信度】pathId={} 负面反馈 conf={}", pathId, path.getConfidence());

                // 连续失败 5 次 → 归档
                if (path.getFailCount() >= 5 && path.getConfidence() < 0.3) {
                    mapper.archive(pathId);
                    embeddingService.deletePath(pathId);
                    log.info("【路径归档】pathId={} 连续失败过多，已归档", pathId);
                }
            }

            mapper.updateConfidence(path);

            // 4. 条件触发 Critic 分析
            if (criticTrigger.shouldTrigger(trace, signal)) {
                CriticVerdict verdict = criticEvaluator.evaluate(trace, signal, userMessage);
                if (verdict != null) {
                    // 写 Critic 评价
                    PathEvaluation eval = PathEvaluation.builder()
                            .pathId(pathId)
                            .traceId(traceId)
                            .userReaction(userMessage)
                            .userReactionType(signal.getType().name().toLowerCase())
                            .criticTriggered(true)
                            .criticScore(verdict.getScore())
                            .criticVerdict(verdict.getVerdict())
                            .criticRootCause(verdict.getRootCause())
                            .criticRuleGenerated(verdict.getLearnedRule())
                            .build();
                    mapper.insertEvaluation(eval);

                    // 规则绑定到路径（下次 RAG 检索命中此路径时自动注入）
                    if (verdict.hasRule()) {
                        path.setCriticRules(appendRule(path.getCriticRules(), verdict.getLearnedRule()));
                        mapper.updateConfidence(path);
                    }
                }
            } else {
                // 无 Critic → 写简单评价
                PathEvaluation eval = PathEvaluation.builder()
                        .pathId(pathId)
                        .traceId(traceId)
                        .userReaction(userMessage)
                        .userReactionType(signal.getType().name().toLowerCase())
                        .build();
                mapper.insertEvaluation(eval);
            }

            trace.setOutcome(signal.isPositive() ? "success" : "failure");
            mapper.updateTraceFeedback(trace);

        } catch (Exception e) {
            log.error("【反馈收集】失败: {}", e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 内部
    // ════════════════════════════════════════════════════════════════

    private BehaviorPath createNewPath(DecisionTrace trace, List<Map<String, Object>> toolCalls) {
        String pathId = "path_" + UUID.randomUUID().toString().substring(0, 12);
        String toolChainJson = toJson(toolCalls);

        // 生成触发摘要（取用户消息前120字）
        String eventText = trace.getEventPayload();
        String summary = eventText != null ? eventText.substring(0, Math.min(eventText.length(), 120)) : "";

        // 生成 embedding
        List<Float> embedding = null;
        try {
            // 从 eventPayload 提取原始消息
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(eventText, Map.class);
            String msg = (String) payload.get("message");
            if (msg != null) {
                embedding = embeddingService.embed(msg);
            }
        } catch (Exception e) {
            log.warn("【新建路径】提取消息失败: {}", e.getMessage());
        }

        BehaviorPath path = BehaviorPath.builder()
                .pathId(pathId)
                .userId(trace.getUserId())
                .triggerIntent("generic")  // Phase 1 不依赖意图识别
                .triggerSummary(summary)
                .triggerEmbedding(embedding != null ? embedding.toString() : null)
                .toolChain(toolChainJson)
                .outcome("success")
                .alpha(1).beta(1)
                .useCount(1).successCount(1).failCount(0)
                .lastUsed(Instant.now())
                .build();

        mapper.insert(path);

        // 索引到 Qdrant
        if (embedding != null && !embedding.isEmpty()) {
            embeddingService.indexPath(path, embedding);
        }

        return path;
    }

    @SuppressWarnings("unchecked")
    private String extractBestPathId(String matchedPathsJson) {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(matchedPathsJson, List.class);
            if (list != null && !list.isEmpty()) {
                return (String) list.get(0).get("pathId");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 时间衰减因子：越久未使用的路径，检索得分越低。
     */
    private double recencyDecay(BehaviorPath path) {
        if (path.getLastUsed() == null) return 1.0;
        long days = ChronoUnit.DAYS.between(path.getLastUsed(), Instant.now());
        if (days < 7) return 1.0;
        if (days < 30) return 0.8;
        if (days < 90) return 0.5;
        return 0.2;
    }

    /** 获取规则注入器（供 AgentGraphRunner 在构建 system prompt 时使用） */
    public RuleInjector getRuleInjector() {
        return ruleInjector;
    }

    /**
     * 向已有规则列表追加一条新规则，返回更新后的 JSON 数组字符串。
     */
    private String appendRule(String existingRules, String newRule) {
        try {
            @SuppressWarnings("unchecked")
            List<String> rules = (existingRules != null && !existingRules.isBlank())
                    ? objectMapper.readValue(existingRules, List.class)
                    : new ArrayList<>();
            if (!rules.contains(newRule)) {
                rules.add(newRule);
            }
            return objectMapper.writeValueAsString(rules);
        } catch (Exception e) {
            return "[\"" + newRule + "\"]";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    // 策略决策结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 检索+策略决策结果。
     */
    public record PathDecision(String strategy, String suggestedToolChain,
                                BehaviorPath matchedPath, DecisionTrace trace,
                                String criticRules) {

        public boolean isHotPath() { return "fast_path".equals(strategy); }
        public boolean isHybrid() { return "hybrid".equals(strategy); }
        public boolean isColdStart() { return "slow_path".equals(strategy); }

        public boolean hasCriticRules() {
            return criticRules != null && !criticRules.isBlank();
        }

        public static PathDecision coldStart() {
            return new PathDecision("slow_path", null, null, null, null);
        }
    }
}
