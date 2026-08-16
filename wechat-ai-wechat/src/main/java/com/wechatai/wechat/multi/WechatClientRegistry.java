package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.wechatai.common.enums.ClientStatus;

import java.util.List;

/**
 * 多 Bot 客户端注册中心。
 * <p>
 * 管理所有微信 Bot 的 ILinkClient 生命周期：创建、登录、心跳、重建、关闭。
 * 每个 client 通过唯一的 {@code clientId} 标识。
 */
public interface WechatClientRegistry {

    /**
     * 创建并注册一个新的 Bot 客户端，自动启动登录流程。
     *
     * @param clientId 客户端唯一标识
     * @return 二维码 URL（需要扫码时），已恢复登录时返回 null
     */
    String register(String clientId);

    /**
     * 获取指定 client 的 ILinkClient 实例。
     *
     * @throws com.wechatai.wechat.exception.ClientNotFoundException 如果 clientId 不存在
     */
    ILinkClient getClient(String clientId);

    /**
     * 获取指定 client 的当前状态。
     */
    ClientStatus getStatus(String clientId);

    /**
     * 列出所有已注册的 clientId。
     */
    List<String> listClientIds();

    /**
     * 关闭并移除指定 client。
     */
    void remove(String clientId);

    /**
     * 获取当前注册的 client 数量。
     */
    int count();

    /**
     * 强制指定 client 重新登录（删除旧登录态，生成新二维码）。
     *
     * @return 新二维码 URL
     */
    String relogin(String clientId);

    /**
     * 获取指定 client 的当前二维码 URL（仅在 WAITING_FOR_SCAN 状态有效）。
     */
    String getQrcodeUrl(String clientId);

    /**
     * 记录哪个用户最近通过哪个 Bot 发了消息，用于回复时路由到正确的 Bot。
     */
    void recordUserClient(String userId, String clientId);

    /**
     * 查找用户最近关联的 Bot clientId，没找到返回 null。
     */
    String findClientForUser(String userId);
}
