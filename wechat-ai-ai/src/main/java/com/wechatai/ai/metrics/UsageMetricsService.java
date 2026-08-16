package com.wechatai.ai.metrics;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 桌面端用量采集（进程内内存，重启清空）— LLM token / 耗时 / 请求数。
 * <p>
 * 通过 ThreadLocal 绑定一轮对话；同一线程内的多次 {@link #recordLlmCall} / {@link #recordTool}
 * 会归入该轮，供「使用统计」页展示。
 */
@Service
public class UsageMetricsService {

    private static final int MAX_TURNS_PER_USER = 100;
    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final ThreadLocal<TurnBuilder> CURRENT = new ThreadLocal<>();

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<TurnRecord>> byUser =
            new ConcurrentHashMap<>();

    public void beginTurn(String userId, String sessionId) {
        if (userId == null || userId.isBlank()) return;
        CURRENT.set(new TurnBuilder(userId, sessionId));
    }

    /** 供并行子 Agent 线程挂回同一轮统计上下文 */
    public Object captureTurnContext() {
        return CURRENT.get();
    }

    public void runWithTurnContext(Object ctx, Runnable action) {
        TurnBuilder previous = CURRENT.get();
        try {
            if (ctx instanceof TurnBuilder tb) {
                CURRENT.set(tb);
            } else {
                CURRENT.remove();
            }
            action.run();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }

    public void recordLlmCall(long elapsedMs, int promptTokens, int completionTokens, int totalTokens,
                              boolean hasToolCalls) {
        TurnBuilder b = CURRENT.get();
        if (b == null) return;
        b.llmCalls.add(new LlmCallRecord(
                elapsedMs,
                Math.max(0, promptTokens),
                Math.max(0, completionTokens),
                Math.max(0, totalTokens > 0 ? totalTokens : promptTokens + completionTokens),
                hasToolCalls,
                Instant.now()
        ));
    }

    public void recordTool(String toolName, long elapsedMs, boolean success) {
        TurnBuilder b = CURRENT.get();
        if (b == null) return;
        String name = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        b.toolCalls.add(new ToolCallRecord(name, elapsedMs, success, Instant.now()));
    }

    public void endTurn(int latencyMs) {
        TurnBuilder b = CURRENT.get();
        CURRENT.remove();
        if (b == null) return;

        List<LlmCallRecord> llmSnapshot;
        List<ToolCallRecord> toolSnapshot;
        synchronized (b.llmCalls) {
            llmSnapshot = List.copyOf(b.llmCalls);
        }
        synchronized (b.toolCalls) {
            toolSnapshot = List.copyOf(b.toolCalls);
        }

        int prompt = 0, completion = 0, total = 0;
        for (LlmCallRecord c : llmSnapshot) {
            prompt += c.promptTokens();
            completion += c.completionTokens();
            total += c.totalTokens();
        }

        TurnRecord turn = new TurnRecord(
                Instant.now(),
                b.userId,
                b.sessionId,
                Math.max(0, latencyMs),
                llmSnapshot,
                toolSnapshot,
                prompt,
                completion,
                total
        );

        ConcurrentLinkedDeque<TurnRecord> q = byUser.computeIfAbsent(
                b.userId, k -> new ConcurrentLinkedDeque<>());
        q.addFirst(turn);
        while (q.size() > MAX_TURNS_PER_USER) {
            q.pollLast();
        }
    }

    /** 取消未完成的一轮（异常路径也要清理 ThreadLocal） */
    public void abandonTurn() {
        CURRENT.remove();
    }

    public Map<String, Object> snapshot(String userId, int recentLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) {
            out.put("summary", emptySummary());
            out.put("recentTurns", List.of());
            out.put("note", "进程内统计，服务重启后清空");
            return out;
        }

        List<TurnRecord> turns = new ArrayList<>(
                byUser.getOrDefault(userId, new ConcurrentLinkedDeque<>()));

        int limit = Math.min(Math.max(recentLimit, 1), 50);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, turns.size()); i++) {
            recent.add(toTurnMap(turns.get(i)));
        }

        out.put("summary", summarize(turns));
        out.put("recentTurns", recent);
        out.put("note", "进程内统计，服务重启后清空；仅统计本服务启动后的桌面对话");
        return out;
    }

    private Map<String, Object> summarize(List<TurnRecord> turns) {
        Map<String, Object> s = emptySummary();
        if (turns.isEmpty()) return s;

        long totalLatency = 0;
        int llmCalls = 0;
        int toolCalls = 0;
        long prompt = 0, completion = 0, totalTokens = 0;
        for (TurnRecord t : turns) {
            totalLatency += t.latencyMs();
            llmCalls += t.llmCalls().size();
            toolCalls += t.toolCalls().size();
            prompt += t.promptTokens();
            completion += t.completionTokens();
            totalTokens += t.totalTokens();
        }
        int n = turns.size();
        s.put("totalTurns", n);
        s.put("totalLlmCalls", llmCalls);
        s.put("totalToolCalls", toolCalls);
        s.put("totalPromptTokens", prompt);
        s.put("totalCompletionTokens", completion);
        s.put("totalTokens", totalTokens);
        s.put("avgLatencyMs", n == 0 ? 0 : totalLatency / n);
        s.put("avgTokensPerTurn", n == 0 ? 0 : totalTokens / n);
        return s;
    }

    private static Map<String, Object> emptySummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalTurns", 0);
        s.put("totalLlmCalls", 0);
        s.put("totalToolCalls", 0);
        s.put("totalPromptTokens", 0);
        s.put("totalCompletionTokens", 0);
        s.put("totalTokens", 0);
        s.put("avgLatencyMs", 0);
        s.put("avgTokensPerTurn", 0);
        return s;
    }

    private Map<String, Object> toTurnMap(TurnRecord t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", DTF.format(t.at()));
        m.put("sessionId", t.sessionId());
        m.put("latencyMs", t.latencyMs());
        m.put("llmCallCount", t.llmCalls().size());
        m.put("toolCallCount", t.toolCalls().size());
        m.put("promptTokens", t.promptTokens());
        m.put("completionTokens", t.completionTokens());
        m.put("totalTokens", t.totalTokens());

        List<Map<String, Object>> llm = new ArrayList<>();
        int i = 1;
        for (LlmCallRecord c : t.llmCalls()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i++);
            row.put("elapsedMs", c.elapsedMs());
            row.put("promptTokens", c.promptTokens());
            row.put("completionTokens", c.completionTokens());
            row.put("totalTokens", c.totalTokens());
            row.put("hasToolCalls", c.hasToolCalls());
            llm.add(row);
        }
        m.put("llmCalls", llm);

        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallRecord c : t.toolCalls()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.name());
            row.put("elapsedMs", c.elapsedMs());
            row.put("success", c.success());
            tools.add(row);
        }
        m.put("toolCalls", tools);
        return m;
    }

    private static final class TurnBuilder {
        final String userId;
        final String sessionId;
        final List<LlmCallRecord> llmCalls = Collections.synchronizedList(new ArrayList<>());
        final List<ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());

        TurnBuilder(String userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }
    }

    private record LlmCallRecord(long elapsedMs, int promptTokens, int completionTokens,
                                 int totalTokens, boolean hasToolCalls, Instant at) {}

    private record ToolCallRecord(String name, long elapsedMs, boolean success, Instant at) {}

    private record TurnRecord(Instant at, String userId, String sessionId, int latencyMs,
                              List<LlmCallRecord> llmCalls, List<ToolCallRecord> toolCalls,
                              int promptTokens, int completionTokens, int totalTokens) {}
}
