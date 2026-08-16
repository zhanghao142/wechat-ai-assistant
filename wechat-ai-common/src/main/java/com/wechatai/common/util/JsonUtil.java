package com.wechatai.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全局 JSON 序列化/反序列化工具。
 * <p>
 * 封装共享 {@link ObjectMapper}，供任务 payload、Redis 缓存、工具参数等场景使用；
 * 后续可按需统一日期格式、忽略未知字段等配置。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialize failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON deserialize failed", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
