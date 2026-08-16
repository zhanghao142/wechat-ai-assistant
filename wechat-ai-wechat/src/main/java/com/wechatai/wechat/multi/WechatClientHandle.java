package com.wechatai.wechat.multi;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.wechatai.common.enums.ClientStatus;

/**
 * 单个 Bot 客户端的操作句柄。
 * <p>
 * 封装 ILinkClient 的发送/下载操作，调用方不直接持有 ILinkClient 引用。
 */
public interface WechatClientHandle {

    /** 获取此 handle 对应的 clientId */
    String getClientId();

    /** 获取当前状态 */
    ClientStatus getStatus();

    /** 发送文本消息 */
    void sendText(String toUser, String text);

    /** 发送文件 */
    void sendFile(String toUser, byte[] data, String fileName, String ext);

    /** 发送行内图片 */
    void sendImage(String toUser, byte[] data, String fileName, String ext);

    /** 下载图片消息的图片数据 */
    byte[] downloadImage(MessageItem item);

    /** 下载文件消息的文件数据 */
    byte[] downloadFile(MessageItem item);
}
