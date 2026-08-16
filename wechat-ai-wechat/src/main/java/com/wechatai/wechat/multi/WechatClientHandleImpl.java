package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.wechatai.common.enums.ClientStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link WechatClientHandle} 的 ILinkClient 包装实现。
 * <p>
 * 每次操作通过 {@link WechatClientRegistry} 获取最新的 ILinkClient 引用，
 * 避免 client 重建后持有过期引用。
 */
@Slf4j
public class WechatClientHandleImpl implements WechatClientHandle {

    private final String clientId;
    private final WechatClientRegistry registry;

    public WechatClientHandleImpl(String clientId, WechatClientRegistry registry) {
        this.clientId = clientId;
        this.registry = registry;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public ClientStatus getStatus() {
        return registry.getStatus(clientId);
    }

    @Override
    public void sendText(String toUser, String text) {
        try {
            ILinkClient client = registry.getClient(clientId);
            client.sendText(toUser, text);
            log.debug("[多账号] client={} 发送文本 → {}", clientId, toUser);
        } catch (Exception e) {
            log.error("[多账号] client={} 发送文本失败 toUser={}", clientId, toUser, e);
        }
    }

    @Override
    public void sendFile(String toUser, byte[] data, String fileName, String ext) {
        try {
            ILinkClient client = registry.getClient(clientId);
            client.sendFile(toUser, data, fileName, ext);
            log.debug("[多账号] client={} 发送文件 {} → {}", clientId, fileName, toUser);
        } catch (Exception e) {
            log.error("[多账号] client={} 发送文件失败 toUser={}", clientId, toUser, e);
        }
    }

    @Override
    public void sendImage(String toUser, byte[] data, String fileName, String ext) {
        try {
            ILinkClient client = registry.getClient(clientId);
            client.sendImage(toUser, data, fileName, ext);
            log.debug("[多账号] client={} 发送图片 {} → {}", clientId, fileName, toUser);
        } catch (Exception e) {
            log.error("[多账号] client={} 发送图片失败 toUser={}", clientId, toUser, e);
        }
    }

    @Override
    public byte[] downloadImage(MessageItem item) {
        try {
            ILinkClient client = registry.getClient(clientId);
            return client.downloadImageFromMessageItem(item);
        } catch (Exception e) {
            log.error("[多账号] client={} 下载图片失败", clientId, e);
            throw new RuntimeException("下载图片失败", e);
        }
    }

    @Override
    public byte[] downloadFile(MessageItem item) {
        try {
            ILinkClient client = registry.getClient(clientId);
            return client.downloadFileFromMessageItem(item);
        } catch (Exception e) {
            log.error("[多账号] client={} 下载文件失败", clientId, e);
            throw new RuntimeException("下载文件失败", e);
        }
    }
}
