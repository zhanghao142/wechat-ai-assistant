package com.wechatai.ai.agent;

import com.wechatai.agent.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多子 Agent 并行批次 — 保留已完成结果，WRITE 确认按停下先后排队。
 * <p>
 * 流程：并行跑完/停住 → 确认队列按 stoppedAt 排序 → 用户逐个确认并 resume →
 * 全部完成后 {@link #aggregate(String)} 汇总。
 */
@Service
public class ParallelAgentBatchService {

    private static final Logger log = LoggerFactory.getLogger(ParallelAgentBatchService.class);

    private final ConcurrentHashMap<String, Batch> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> threadIdToSession = new ConcurrentHashMap<>();

    public void register(Batch batch) {
        if (batch == null || batch.sessionId == null || batch.sessionId.isBlank()) return;
        clear(batch.sessionId);
        batch.confirmQueue.sort(Comparator.comparingLong(c -> c.stoppedAtMs));
        bySession.put(batch.sessionId, batch);
        for (ConfirmItem c : batch.confirmQueue) {
            if (c.threadId != null) {
                threadIdToSession.put(c.threadId, batch.sessionId);
            }
        }
        log.info("【并行批次】注册 sessionId={} slots={} confirms={} 队头={}",
                batch.sessionId, batch.slots.size(), batch.confirmQueue.size(),
                batch.confirmQueue.isEmpty() ? "-" : batch.confirmQueue.get(0).agentName);
    }

    public Batch getBySession(String sessionId) {
        if (sessionId == null) return null;
        return bySession.get(sessionId);
    }

    public Batch getByThreadId(String threadId) {
        if (threadId == null) return null;
        String sid = threadIdToSession.get(threadId);
        return sid == null ? null : bySession.get(sid);
    }

    public boolean hasPendingConfirm(String sessionId) {
        Batch b = getBySession(sessionId);
        return b != null && !b.confirmQueue.isEmpty();
    }

    public ConfirmItem peekConfirm(String sessionId) {
        Batch b = getBySession(sessionId);
        if (b == null || b.confirmQueue.isEmpty()) return null;
        return b.confirmQueue.get(0);
    }

    /**
     * 用户确认/取消队头 Agent 后的结果处理。
     *
     * @return NEXT_CONFIRM / FINISHED / ERROR
     */
    public ResumeOutcome onResumeResult(String sessionId, AgentResult result) {
        Batch b = getBySession(sessionId);
        if (b == null) {
            return ResumeOutcome.none();
        }
        if (b.confirmQueue.isEmpty()) {
            return ResumeOutcome.finished(b);
        }

        ConfirmItem head = b.confirmQueue.remove(0);
        if (head.threadId != null) {
            threadIdToSession.remove(head.threadId);
        }

        if (result == null) {
            b.markDone(head.requestId, head.agentName, "操作失败", false, List.of());
            return afterSlotDone(b);
        }

        if (result.needsConfirm()) {
            ConfirmItem again = new ConfirmItem(
                    head.requestId,
                    head.agentName,
                    result.getConfirmThreadId(),
                    result.getConfirmToolName(),
                    result.getSummary(),
                    System.currentTimeMillis()
            );
            b.confirmQueue.add(0, again);
            if (again.threadId != null) {
                threadIdToSession.put(again.threadId, sessionId);
            }
            log.info("【并行批次】{} 再次 WRITE 确认，仍排在队头 threadId={}",
                    head.agentName, again.threadId);
            return ResumeOutcome.nextConfirm(again, b.confirmQueue.size());
        }

        if (result.isOk()) {
            b.markDone(head.requestId, head.agentName, result.getSummary(), true,
                    result.getAttachments());
        } else {
            String err = result.getError() != null ? result.getError() : "操作失败";
            b.markDone(head.requestId, head.agentName, err, false, List.of());
        }
        return afterSlotDone(b);
    }

    private ResumeOutcome afterSlotDone(Batch b) {
        if (!b.confirmQueue.isEmpty()) {
            ConfirmItem next = b.confirmQueue.get(0);
            log.info("【并行批次】还有 {} 项待确认，下一项 {} threadId={}",
                    b.confirmQueue.size(), next.agentName, next.threadId);
            return ResumeOutcome.nextConfirm(next, b.confirmQueue.size());
        }
        log.info("【并行批次】全部确认/完成，可汇总 sessionId={}", b.sessionId);
        return ResumeOutcome.finished(b);
    }

    public String aggregate(Batch b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (SlotResult slot : b.slots) {
            String text = slot.finalText;
            if (text == null || text.isBlank()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text.strip());
        }
        return sb.toString().strip();
    }

    public List<Map<String, Object>> allAttachments(Batch b) {
        if (b == null) return List.of();
        List<Map<String, Object>> all = new ArrayList<>(b.extraAttachments);
        for (SlotResult slot : b.slots) {
            if (slot.attachments != null) {
                all.addAll(slot.attachments);
            }
        }
        return all;
    }

    public void clear(String sessionId) {
        if (sessionId == null) return;
        Batch removed = bySession.remove(sessionId);
        if (removed != null) {
            for (ConfirmItem c : removed.confirmQueue) {
                if (c.threadId != null) threadIdToSession.remove(c.threadId);
            }
            log.info("【并行批次】清除 sessionId={}", sessionId);
        }
    }

    // ── 模型 ──

    public static final class Batch {
        public final String sessionId;
        public final List<SlotResult> slots = new ArrayList<>();
        public final List<ConfirmItem> confirmQueue = new ArrayList<>();
        public final List<Map<String, Object>> extraAttachments = new ArrayList<>();

        public Batch(String sessionId) {
            this.sessionId = sessionId;
        }

        /** 按原始 tool_calls 顺序追加槽位（含尚未确认的占位） */
        public void addSlot(SlotResult slot) {
            slots.add(slot);
        }

        public void enqueueConfirm(ConfirmItem item) {
            confirmQueue.add(item);
        }

        void markDone(String requestId, String agentName, String text, boolean ok,
                      List<Map<String, Object>> attachments) {
            for (SlotResult s : slots) {
                if (requestId != null && requestId.equals(s.requestId)) {
                    s.finalText = text;
                    s.ok = ok;
                    s.attachments = attachments != null ? attachments : List.of();
                    return;
                }
            }
            slots.add(new SlotResult(requestId, agentName, text, ok, attachments));
        }
    }

    public static final class ConfirmItem {
        public final String requestId;
        public final String agentName;
        public final String threadId;
        public final String toolName;
        public final String confirmMsg;
        public final long stoppedAtMs;

        public ConfirmItem(String requestId, String agentName, String threadId,
                           String toolName, String confirmMsg, long stoppedAtMs) {
            this.requestId = requestId;
            this.agentName = agentName;
            this.threadId = threadId;
            this.toolName = toolName;
            this.confirmMsg = confirmMsg;
            this.stoppedAtMs = stoppedAtMs;
        }
    }

    public static final class SlotResult {
        public final String requestId;
        public final String agentName;
        public String finalText;
        public boolean ok;
        public List<Map<String, Object>> attachments;

        public SlotResult(String requestId, String agentName, String finalText, boolean ok,
                          List<Map<String, Object>> attachments) {
            this.requestId = requestId;
            this.agentName = agentName;
            this.finalText = finalText;
            this.ok = ok;
            this.attachments = attachments != null ? attachments : List.of();
        }
    }

    public static final class ResumeOutcome {
        public enum Kind { NONE, NEXT_CONFIRM, FINISHED }

        public final Kind kind;
        public final ConfirmItem nextConfirm;
        public final int remainingConfirms;
        public final Batch batch;

        private ResumeOutcome(Kind kind, ConfirmItem nextConfirm, int remainingConfirms, Batch batch) {
            this.kind = kind;
            this.nextConfirm = nextConfirm;
            this.remainingConfirms = remainingConfirms;
            this.batch = batch;
        }

        static ResumeOutcome none() {
            return new ResumeOutcome(Kind.NONE, null, 0, null);
        }

        static ResumeOutcome nextConfirm(ConfirmItem item, int remaining) {
            return new ResumeOutcome(Kind.NEXT_CONFIRM, item, remaining, null);
        }

        static ResumeOutcome finished(Batch batch) {
            return new ResumeOutcome(Kind.FINISHED, null, 0, batch);
        }
    }

    /** 组装带队列提示的确认文案 */
    public static String formatConfirmMessage(ConfirmItem item, int queueSize) {
        if (item == null) return "请确认操作";
        String base = item.confirmMsg != null ? item.confirmMsg : "请确认操作";
        if (queueSize <= 1) return base;
        return base + "\n\n（并行任务：还有 " + (queueSize - 1) + " 项待确认）";
    }
}
