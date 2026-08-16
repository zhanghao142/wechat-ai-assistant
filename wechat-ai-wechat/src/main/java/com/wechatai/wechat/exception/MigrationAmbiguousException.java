package com.wechatai.wechat.exception;

/**
 * 旧单文件登录状态迁移时出现歧义（多个文件或无法确定目标）时抛出。
 */
public class MigrationAmbiguousException extends RuntimeException {

    public MigrationAmbiguousException(String msg) {
        super("登录状态迁移失败: " + msg);
    }
}
