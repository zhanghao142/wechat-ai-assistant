package com.wechatai.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应体。
 * <p>
 * 所有对外接口均包装为此结构返回，便于前端与调用方统一解析成功/失败。
 * 业务码见 {@link com.wechatai.common.enums.ErrorCode}；
 * {@code traceId} 用于跨模块链路排查。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 业务码，200 表示成功，其余见错误码表 */
    private int code;
    /** 提示信息，成功一般为 success */
    private String message;
    /** 业务数据体，失败时多为 null */
    private T data;
    /** 链路追踪 ID，便于日志关联排查 */
    private String traceId;
    /** 服务端时间戳（毫秒） */
    private long timestamp;

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data, null, System.currentTimeMillis());
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId, System.currentTimeMillis());
    }
}
