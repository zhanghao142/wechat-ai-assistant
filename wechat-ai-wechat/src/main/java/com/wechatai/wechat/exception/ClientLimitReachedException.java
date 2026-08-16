package com.wechatai.wechat.exception;

/**
 * client 数量达到上限时抛出。
 */
public class ClientLimitReachedException extends RuntimeException {

    public ClientLimitReachedException(int current, int max) {
        super("微信客户端数量已达上限: " + current + "/" + max);
    }
}
