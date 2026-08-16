package com.wechatai.wechat.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.wechatai.wechat.config.WechatMultiAccountProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link WechatLoginStateStore} 的多文件实现。
 * <p>
 * 每个 client 的登录态保存为 {@code {storeDirectory}/{clientId}.json}。
 * 采用三段式原子写入（tmp → main）和 SHA-256 内容比较策略，
 * 避免重复写入和写入中断导致文件损坏。
 */
@Slf4j
public class WechatLoginStateStoreImpl implements WechatLoginStateStore {

    private final Path storeDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WechatLoginStateStoreImpl(WechatMultiAccountProperties props) {
        this.storeDir = Paths.get(props.getLogin().getStoreDirectory());
        try {
            Files.createDirectories(storeDir);
        } catch (Exception e) {
            log.warn("创建登录状态目录失败: {}", storeDir, e);
        }
    }

    @Override
    public void save(String clientId, ILinkClient client) {
        try {
            ResumeContext ctx = client.exportResumeContext();
            if (ctx == null) {
                log.warn("[多账号] client={} exportResumeContext 返回 null，跳过保存", clientId);
                return;
            }
            ClientLoginData data = fromResumeContext(ctx);
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);

            // SHA-256 比较，跳过无变化写入
            Path targetFile = clientFile(clientId);
            if (Files.exists(targetFile)) {
                byte[] existing = Files.readAllBytes(targetFile);
                if (MessageDigest.isEqual(sha256(existing), sha256(jsonBytes))) {
                    return; // 内容相同，跳过
                }
            }

            // 原子写入：先写 tmp，再 rename
            Path tmpFile = Paths.get(targetFile.toString() + ".tmp");
            Files.write(tmpFile, jsonBytes);
            Files.move(tmpFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.debug("[多账号] client={} 登录状态已持久化 → {}", clientId, targetFile);
        } catch (Exception e) {
            log.warn("[多账号] client={} 保存登录状态失败: {}", clientId, e.getMessage());
        }
    }

    @Override
    public ResumeContext load(String clientId) {
        Path file = clientFile(clientId);
        if (!Files.exists(file)) return null;
        try {
            ClientLoginData data = objectMapper.readValue(file.toFile(), ClientLoginData.class);
            return toResumeContext(data);
        } catch (Exception e) {
            log.warn("[多账号] client={} 读取登录状态失败，将重新扫码: {}", clientId, e.getMessage());
            try { Files.delete(file); } catch (Exception ignored) {}
            return null;
        }
    }

    @Override
    public void delete(String clientId) {
        try {
            Path file = clientFile(clientId);
            Files.deleteIfExists(file);
            // 也清理残留 tmp 文件
            Files.deleteIfExists(Paths.get(file.toString() + ".tmp"));
            log.info("[多账号] client={} 登录状态文件已删除", clientId);
        } catch (Exception e) {
            log.warn("[多账号] client={} 删除登录状态文件失败", clientId, e);
        }
    }

    @Override
    public List<String> listStoredClients() {
        File dir = storeDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return Collections.emptyList();
        return Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(String clientId) {
        return Files.exists(clientFile(clientId));
    }

    private Path clientFile(String clientId) {
        return storeDir.resolve(clientId + ".json");
    }

    // ========================================================================
    // ResumeContext ↔ ClientLoginData 互转（复用 WechatLoginStore 的模式）
    // ========================================================================

    private ClientLoginData fromResumeContext(ResumeContext ctx) {
        LoginContext login = ctx.getLoginContext();
        ClientLoginData data = new ClientLoginData();
        data.setBotToken(login.getBotToken());
        data.setUserId(login.getUserId());
        data.setBotId(login.getBotId());
        data.setBaseUrl(login.getBaseUrl());
        data.setUpdatesCursor(ctx.getUpdatesCursor());

        Collection<ConversationContext> convs = ctx.getConversationContexts();
        if (convs != null && !convs.isEmpty()) {
            data.setConversations(convs.stream().map(cc -> {
                ClientLoginData.ConvDTO dto = new ClientLoginData.ConvDTO();
                dto.setUserId(cc.getKey().getUserId());
                dto.setBotId(cc.getKey().getBotId());
                dto.setLatestContextToken(cc.getLatestContextToken());
                dto.setTypingTicket(cc.getTypingTicket());
                dto.setLastUpdatedAt(cc.getLastUpdatedAt());
                dto.setSourceMessageId(cc.getSourceMessageId());
                dto.setSourceMessageTime(cc.getSourceMessageTime());
                return dto;
            }).collect(Collectors.toList()));
        }
        return data;
    }

    private ResumeContext toResumeContext(ClientLoginData data) {
        LoginContext login = new LoginContext(data.getBotToken(), data.getUserId(), data.getBotId(), data.getBaseUrl());
        ResumeContext.Builder builder = ResumeContext.builder(login);

        if (data.getUpdatesCursor() != null) {
            builder.updatesCursor(data.getUpdatesCursor());
        }

        if (data.getConversations() != null && !data.getConversations().isEmpty()) {
            Map<String, ConversationContext> map = new HashMap<>();
            for (ClientLoginData.ConvDTO dto : data.getConversations()) {
                ContextKey key = new ContextKey(dto.getBotId(), dto.getUserId());
                ConversationContext cc = new ConversationContext(key);
                if (dto.getLatestContextToken() != null) cc.setLatestContextToken(dto.getLatestContextToken());
                if (dto.getTypingTicket() != null) cc.setTypingTicket(dto.getTypingTicket());
                if (dto.getLatestContextToken() != null && dto.getSourceMessageId() != null && dto.getSourceMessageTime() != null) {
                    cc.updateContextToken(dto.getLatestContextToken(), dto.getSourceMessageId(), dto.getSourceMessageTime());
                }
                map.put(dto.getUserId(), cc);
            }
            builder.conversationContexts(map);
        }
        return builder.build();
    }

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return data; // fallback
        }
    }
}
