package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;

import java.util.List;

/**
 * 多账号登录状态持久化接口。
 * <p>
 * 每个 client 的登录态保存为独立文件：{@code {storeDirectory}/{clientId}.json}。
 * 单账号模式使用现有的 {@link WechatLoginStore}（单文件），此接口仅多账号模式激活。
 */
public interface WechatLoginStateStore {

    /**
     * 持久化指定 client 的登录状态。
     */
    void save(String clientId, ILinkClient client);

    /**
     * 加载指定 client 的登录状态，文件不存在或损坏返回 null。
     */
    ResumeContext load(String clientId);

    /**
     * 删除指定 client 的登录状态文件。
     */
    void delete(String clientId);

    /**
     * 列出存储目录中所有已有的 clientId。
     */
    List<String> listStoredClients();

    /**
     * 判断指定 client 的登录状态文件是否存在。
     */
    boolean exists(String clientId);
}
