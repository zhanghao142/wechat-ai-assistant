package com.wechatai.wechat.exception;

/**
 * 指定 clientId 不存在时抛出。
 */
public class ClientNotFoundException extends RuntimeException {

    public ClientNotFoundException(String clientId) {
        super("微信客户端不存在: " + clientId);
    }
}
