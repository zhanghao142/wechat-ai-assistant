package com.wechatai.common.constant;

/**
 * Redis Key 前缀集中管理，避免魔法字符串散落。
 * <p>
 * Redis 在本项目中承担三类角色：
 * <ul>
 *   <li>缓存：加速会话元数据、文档摘要、Token 读取</li>
 *   <li>队列：文档解析等异步任务解耦</li>
 *   <li>限流：按用户频率控制</li>
 * </ul>
 * MySQL 为最终主库；Redis 允许短时不一致。TTL 见各常量注释。
 */
public interface RedisKeys {

    /** 会话元数据：session:{sessionId}，TTL 约 24h */
    String SESSION_PREFIX   = "session:";
    /** 最近消息列表后缀：session:{sessionId}:messages，TTL 约 24h */
    String SESSION_MESSAGES = ":messages";
    /** 文档文本摘要缓存：file:content:{fileId}，TTL 约 24h */
    String FILE_CONTENT     = "file:content:";
    /** 文档解析任务队列（List），无固定 TTL */
    String TASK_QUEUE_DOC   = "task:queue:doc_parse";
    /** 任务状态：task:status:{taskId}，TTL 约 72h */
    String TASK_STATUS      = "task:status:";
    /** 微信 Access Token，按微信侧过期时间续期 */
    String WECHAT_TOKEN     = "wechat:token";
    /** 用户限流：rate:limit:{userId}，TTL 约 1h */
    String RATE_LIMIT       = "rate:limit:";
}
