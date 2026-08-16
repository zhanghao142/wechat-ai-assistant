package com.wechatai.wechat.multi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 客户端登录数据 POJO — JSON 序列化中转。
 * <p>
 * 复用 {@link WechatLoginStore} 的内部类模式（StoreData + ConvDTO），
 * 将 SDK 的 {@code ResumeContext} 转为可序列化的纯字段 POJO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientLoginData {

    private int schemaVersion = 1;
    private String botToken;
    private String userId;
    private String botId;
    private String baseUrl;
    private String updatesCursor;
    private List<ConvDTO> conversations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConvDTO {
        private String userId;
        private String botId;
        private String latestContextToken;
        private String typingTicket;
        private Long lastUpdatedAt;
        private Long sourceMessageId;
        private Long sourceMessageTime;
    }
}
