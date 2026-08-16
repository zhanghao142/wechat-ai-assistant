package com.wechatai.common.model;

/**
 * 对话隔离键工具类 — {@code clientId + ":" + fromUserId} 复合键。
 * <p>
 * 多账号架构中，同一个微信用户对不同 Bot 拥有独立的 AI 对话上下文。
 * 此工具类提供复合键的构造和解析，同时兼容单账号模式（key 即 fromUserId）。
 * <p>
 * 格式：{@code clientId:fromUserId}，分隔符为 {@code :}。
 * 单账号模式下 conversationKey 直接使用 fromUserId（不含冒号），
 * 此时 {@link #clientId(String)} 返回 {@code "default"}。
 */
public final class ConversationKey {

    private static final String SEPARATOR = ":";
    private static final String DEFAULT_CLIENT = "default";

    private ConversationKey() {
        // 工具类，禁止实例化
    }

    /**
     * 构造复合隔离键。
     *
     * @param clientId   Bot 客户端标识
     * @param fromUserId 微信用户 ID
     * @return {@code "clientId:fromUserId"} 格式的复合键
     */
    public static String of(String clientId, String fromUserId) {
        if (clientId == null || clientId.isEmpty()) {
            return fromUserId;
        }
        return clientId + SEPARATOR + fromUserId;
    }

    /**
     * 从 conversationKey 中提取 clientId。
     * 不含冒号时返回 {@code "default"}（单账号兼容）。
     */
    public static String clientId(String conversationKey) {
        if (conversationKey == null) return DEFAULT_CLIENT;
        int idx = conversationKey.indexOf(SEPARATOR);
        return idx < 0 ? DEFAULT_CLIENT : conversationKey.substring(0, idx);
    }

    /**
     * 从 conversationKey 中提取 fromUserId。
     * 不含冒号时返回整个 key（单账号兼容）。
     */
    public static String fromUserId(String conversationKey) {
        if (conversationKey == null) return null;
        int idx = conversationKey.indexOf(SEPARATOR);
        return idx < 0 ? conversationKey : conversationKey.substring(idx + 1);
    }

    /**
     * 判断是否为多账号复合键（含冒号分隔符）。
     */
    public static boolean isComposite(String conversationKey) {
        return conversationKey != null && conversationKey.indexOf(SEPARATOR) > 0;
    }
}
