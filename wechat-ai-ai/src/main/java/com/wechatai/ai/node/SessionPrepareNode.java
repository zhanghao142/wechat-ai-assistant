package com.wechatai.ai.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.session.entity.MessageEntity;
import com.wechatai.session.entity.SessionEntity;
import com.wechatai.session.mapper.MessageMapper;
import com.wechatai.session.service.SessionService;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 会话准备节点 — 获取/续期活跃会话，从 MySQL 加载历史消息。
 * <p>
 * 缓存优先策略：
 * <ol>
 *   <li>如果 state 已有 messageHistory（WechatServiceImpl 从 ConcurrentHashMap 传入），直接跳过查库</li>
 *   <li>没有缓存时（首次对话 / 重启），从 MySQL message 表加载最近 50 条</li>
 * </ol>
 * 写操作由 LlmService 和 ToolExecuteNode 在每次 LLM 调用后同步写入 MySQL。
 */
public class SessionPrepareNode implements AsyncNodeAction<ChatGraphState> {

    private static final Logger log = LoggerFactory.getLogger(SessionPrepareNode.class);

    /** 从 MySQL 加载的最大消息数（与 LlmService 150 轮一致，150轮×2=300条非system消息） */
    private static final int HISTORY_LIMIT = 300;

    private final SessionService sessionService;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public SessionPrepareNode(SessionService sessionService, MessageMapper messageMapper) {
        this.sessionService = sessionService;
        this.messageMapper = messageMapper;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(ChatGraphState state) {
        // 桌面渠道：保留请求传入的 sessionId，禁止被 getOrCreateActiveSession 覆盖
        // （否则提醒会写到微信活跃会话，桌面当前会话收不到）
        String channel = state.getChannel();
        String incomingSessionId = state.getSessionId();
        boolean desktopFixedSession = "DESKTOP".equalsIgnoreCase(channel)
                && incomingSessionId != null && !incomingSessionId.isBlank();

        String sessionId;
        if (desktopFixedSession) {
            // 桌面：沿用客户端 sessionId，并确保写入 session 表（挂到当前 userId）
            sessionId = incomingSessionId;
            sessionService.ensureDesktopSession(state.getUserId(), sessionId);
        } else {
            SessionEntity session = sessionService.getOrCreateActiveSession(state.getUserId());
            sessionId = session.getSessionId();
        }

        // 2. 缓存优先：如果已有 messageHistory（从 WechatServiceImpl 传入），不再查 MySQL
        List<Map<String, Object>> existing = (List<Map<String, Object>>) state.data().get("messageHistory");
        if (existing != null) {
            return CompletableFuture.completedFuture(Map.of(
                    ChatGraphState.KEY_SESSION_ID, sessionId
            ));
        }

        // 3. 缓存未命中 → 从 MySQL 加载（桌面用客户端 sessionId）
        List<MessageEntity> recentMessages = messageMapper.findLatestBySessionId(sessionId, HISTORY_LIMIT);

        List<Map<String, Object>> messageHistory = new ArrayList<>();
        if (recentMessages != null && !recentMessages.isEmpty()) {
            // DB 是倒序（DESC），需要反转成时间正序
            Collections.reverse(recentMessages);
            for (MessageEntity msg : recentMessages) {
                Map<String, Object> msgMap = new LinkedHashMap<>();
                String role = msg.getRole().name().toLowerCase();
                msgMap.put("role", role);
                msgMap.put("content", msg.getContent() != null ? msg.getContent() : "");

                // TOOL 角色需要 tool_call_id（DeepSeek API 要求）
                if ("tool".equals(role) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    try {
                        Map<String, Object> tcMap = objectMapper.readValue(
                                msg.getToolCalls(), new TypeReference<Map<String, Object>>() {});
                        Object tcId = tcMap.get("tool_call_id");
                        if (tcId != null) {
                            msgMap.put("tool_call_id", tcId);
                        }
                    } catch (Exception ignored) {
                    }
                }

                // ASSISTANT 的 tool_calls 字段（JSON 数组）
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty() && "assistant".equals(role)) {
                    try {
                        List<Map<String, Object>> toolCalls = objectMapper.readValue(
                                msg.getToolCalls(), new TypeReference<List<Map<String, Object>>>() {});
                        msgMap.put("tool_calls", toolCalls);
                    } catch (Exception ignored) {
                    }
                }
                messageHistory.add(msgMap);
            }
        }

        // 4. 修复 tool 链断裂：扫描全部消息，裁掉孤儿 assistant(tool_calls)
        messageHistory = fixOrphanedToolCalls(messageHistory);

        // 5. 返回 sessionId + MySQL 加载的历史（没有历史就是空列表，LlmService 自动加 system prompt）
        Map<String, Object> result = new HashMap<>();
        result.put(ChatGraphState.KEY_SESSION_ID, sessionId);
        result.put("messageHistory", messageHistory);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 扫描消息列表，确保每个 assistant(tool_calls) 后都有对应的 tool 响应。
     * 如果存在不完整的 tool 链（DB 中残留的损坏数据），裁到最后一个干净的 user 消息处。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fixOrphanedToolCalls(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) return messages;

        messages = collapseConsecutiveAssistantToolCalls(messages);

        int lastCleanUserIdx = -1;
        Set<String> pendingCallIds = new LinkedHashSet<>();

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");

            if ("user".equals(role)) {
                if (pendingCallIds.isEmpty()) {
                    lastCleanUserIdx = i;
                } else {
                    stripOrphanToolCalls(messages, pendingCallIds);
                }
                pendingCallIds.clear();
            } else if ("assistant".equals(role)) {
                Object tcs = msg.get("tool_calls");
                if (tcs instanceof List) {
                    for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                        Object id = tc.get("id");
                        if (id != null) pendingCallIds.add(id.toString());
                    }
                }
            } else if ("tool".equals(role)) {
                Object tcId = msg.get("tool_call_id");
                if (tcId != null) pendingCallIds.remove(tcId.toString());
            }
        }

        if (!pendingCallIds.isEmpty() && lastCleanUserIdx >= 0) {
            List<Map<String, Object>> cleaned = new ArrayList<>(
                    messages.subList(lastCleanUserIdx, messages.size()));
            // 剔除裁后消息中残留的孤儿 tool_calls（assistant 有 tool_calls 但无对应 tool 响应）
            cleaned = stripOrphanToolCalls(cleaned, pendingCallIds);
            log.warn("【SessionPrepare】检测到 DB 中残留孤儿 tool_calls: {}，裁到第 {} 条 user，裁后消息数={}",
                    pendingCallIds, lastCleanUserIdx, cleaned.size());
            return cleaned;
        }

        return messages;
    }

    /**
     * 连续多条 assistant(tool_calls) 时只保留最后一条，禁止合并 ID 数组。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collapseConsecutiveAssistantToolCalls(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            Object tcs = msg.get("tool_calls");
            boolean isAssistantTc = "assistant".equals(msg.get("role"))
                    && tcs instanceof List && !((List<?>) tcs).isEmpty();
            if (!isAssistantTc) {
                out.add(msg);
                continue;
            }
            int j = i + 1;
            while (j < messages.size()) {
                Map<String, Object> next = messages.get(j);
                Object nextTc = next.get("tool_calls");
                boolean nextIsTc = "assistant".equals(next.get("role"))
                        && nextTc instanceof List && !((List<?>) nextTc).isEmpty();
                if (!nextIsTc) break;
                j++;
            }
            if (j > i + 1) {
                log.warn("【SessionPrepare】丢弃连续 {} 条多余 assistant(tool_calls)，只保留最后一条",
                        j - i - 1);
            }
            out.add(messages.get(j - 1));
            i = j - 1;
        }
        return out;
    }

    /** 从消息列表中剔除没有对应 tool 响应的 tool_calls ID，同时清理孤立 tool 消息 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stripOrphanToolCalls(List<Map<String, Object>> messages,
                                                            Set<String> orphanIds) {
        // 第一步：收集所有合法 assistant(tc) 的 call ID
        Set<String> validCallIds = new HashSet<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) continue;
            Object tcs = msg.get("tool_calls");
            if (!(tcs instanceof List)) continue;
            for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                String id = (String) tc.get("id");
                if (id != null && !orphanIds.contains(id)) validCallIds.add(id);
            }
        }

        // 第二步：删掉 tool_call_id 不匹配任何合法 assistant(tc) 的 tool 消息
        java.util.Iterator<Map<String, Object>> it = messages.iterator();
        while (it.hasNext()) {
            Map<String, Object> msg = it.next();
            if (!"tool".equals(msg.get("role"))) continue;
            String tcId = (String) msg.get("tool_call_id");
            if (tcId != null && !validCallIds.contains(tcId)) {
                it.remove();
            }
        }

        // 第三步：删掉 assistant 消息中属于孤儿 ID 的 tool_calls
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;
            Object tcs = msg.get("tool_calls");
            if (!(tcs instanceof List)) continue;
            List<Map<String, Object>> fixed = new ArrayList<>();
            for (Map<String, Object> tc : (List<Map<String, Object>>) tcs) {
                String id = (String) tc.get("id");
                if (id != null && !orphanIds.contains(id)) {
                    fixed.add(tc);
                }
            }
            if (fixed.size() != ((List) tcs).size()) {
                Map<String, Object> copy = new LinkedHashMap<>(msg);
                copy.put("tool_calls", fixed.isEmpty() ? null : fixed);
                messages.set(i, copy);
            }
        }
        return messages;
    }
}
