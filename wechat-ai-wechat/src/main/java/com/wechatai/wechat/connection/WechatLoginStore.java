package com.wechatai.wechat.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 微信登录状态持久化 — JSON ↔ ResumeContext 互转。
 * <p>
 * 利用 SDK 2.3.3 的 {@link ILinkClient#exportResumeContext()} 导出完整登录上下文
 * （含 token、userId、botId、消息游标、各对话上下文），序列化为 JSON 文件。
 * 重启时反序列化重建 {@link ResumeContext}，通过
 * {@code ILinkClientBuilder.resumeContext(ctx)} 跳过扫码直接恢复。
 * <p>
 * 为什么需要这个类：
 * <ul>
 *   <li>SDK 的 ResumeContext 是 Builder 模式，无无参构造，Jackson 无法直接序列化</li>
 *   <li>之前只存 contextToken 的方案失败，因为缺少 botId + updatesCursor + 全部对话上下文</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "false", matchIfMissing = true)
public class WechatLoginStore {

    private static final Logger log = LoggerFactory.getLogger(WechatLoginStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String storePath;

    public WechatLoginStore(
            @Value("${wechat.login.store-path:./wechat-login/login-state.json}") String storePath) {
        this.storePath = storePath;
    }

    /**
     * 从已登录的 client 导出登录状态并保存到 JSON 文件。
     */
    public void save(ILinkClient client) {
        try {
            ResumeContext ctx = client.exportResumeContext();
            if (ctx == null) {
                log.warn("exportResumeContext 返回 null，跳过保存");
                return;
            }
            StoreData data = StoreData.from(ctx);
            File file = new File(storePath);
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
            log.debug("微信登录状态已持久化: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("保存微信登录状态失败: {}", e.getMessage());
        }
    }

    /**
     * 删除持久化的登录状态文件，强制下次启动重新扫码。
     */
    public void delete() {
        File file = new File(storePath);
        if (file.exists()) {
            boolean deleted = file.delete();
            log.info("登录状态文件已删除: {} ({})", file.getAbsolutePath(), deleted ? "成功" : "失败");
        }
    }

    /**
     * 从 JSON 文件加载并重建 ResumeContext。
     * 文件不存在或格式损坏返回 null，调用方走正常扫码流程。
     */
    public ResumeContext load() {
        File file = new File(storePath);
        if (!file.exists()) return null;
        try {
            StoreData data = objectMapper.readValue(file, StoreData.class);
            return data.toResumeContext();
        } catch (Exception e) {
            log.warn("读取微信登录状态失败，将重新扫码: {}", e.getMessage());
            file.delete();
            return null;
        }
    }

    // ========================================================================
    // 内部 POJO — 纯字段容器，无业务逻辑，只做 JSON 中转
    // ========================================================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StoreData {
        private String botToken;
        private String userId;
        private String botId;
        private String baseUrl;
        private String updatesCursor;
        private List<ConvDTO> conversations;

        static StoreData from(ResumeContext ctx) {
            LoginContext login = ctx.getLoginContext();
            StoreData data = new StoreData();
            data.botToken = login.getBotToken();
            data.userId = login.getUserId();
            data.botId = login.getBotId();
            data.baseUrl = login.getBaseUrl();
            data.updatesCursor = ctx.getUpdatesCursor();

            Collection<ConversationContext> convs = ctx.getConversationContexts();
            if (convs != null && !convs.isEmpty()) {
                data.conversations = convs.stream().map(cc -> {
                    ConvDTO dto = new ConvDTO();
                    dto.userId = cc.getKey().getUserId();
                    dto.botId = cc.getKey().getBotId();
                    dto.latestContextToken = cc.getLatestContextToken();
                    dto.typingTicket = cc.getTypingTicket();
                    dto.lastUpdatedAt = cc.getLastUpdatedAt();
                    dto.sourceMessageId = cc.getSourceMessageId();
                    dto.sourceMessageTime = cc.getSourceMessageTime();
                    return dto;
                }).collect(Collectors.toList());
            }
            return data;
        }

        ResumeContext toResumeContext() {
            LoginContext login = new LoginContext(botToken, userId, botId, baseUrl);
            ResumeContext.Builder builder = ResumeContext.builder(login);

            if (updatesCursor != null) {
                builder.updatesCursor(updatesCursor);
            }

            if (conversations != null && !conversations.isEmpty()) {
                Map<String, ConversationContext> map = new HashMap<>();
                for (ConvDTO dto : conversations) {
                    ContextKey key = new ContextKey(dto.botId, dto.userId);
                    ConversationContext cc = new ConversationContext(key);

                    // 有公开 setter 的字段直接设置
                    if (dto.latestContextToken != null) {
                        cc.setLatestContextToken(dto.latestContextToken);
                    }
                    if (dto.typingTicket != null) {
                        cc.setTypingTicket(dto.typingTicket);
                    }

                    // sourceMessageId / sourceMessageTime 通过 updateContextToken 设置
                    //（该方法同时更新 latestContextToken，所以 latest 在前是必要的）
                    if (dto.latestContextToken != null
                            && dto.sourceMessageId != null
                            && dto.sourceMessageTime != null) {
                        cc.updateContextToken(
                                dto.latestContextToken,
                                dto.sourceMessageId,
                                dto.sourceMessageTime
                        );
                    }

                    map.put(dto.userId, cc);
                }
                builder.conversationContexts(map);
            }

            return builder.build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ConvDTO {
        private String userId;
        private String botId;
        private String latestContextToken;
        private String typingTicket;
        private Long lastUpdatedAt;
        private Long sourceMessageId;
        private Long sourceMessageTime;
    }
}
