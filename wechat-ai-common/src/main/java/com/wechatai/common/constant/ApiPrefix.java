package com.wechatai.common.constant;

/**
 * HTTP 接口路径前缀常量。
 * <p>
 * 约定：所有对外 REST 接口均以 {@code /api/v1} 开头，
 * 各业务模块在此基础上拼接资源路径（session/document/chat 等）。
 */
public interface ApiPrefix {

    /** API 版本根路径 */
    String V1       = "/api/v1";
    /** 会话管理：/api/v1/session */
    String SESSION  = V1 + "/session";
    /** 文档管理：/api/v1/document */
    String DOCUMENT = V1 + "/document";
    /** AI 对话：/api/v1/chat */
    String CHAT     = V1 + "/chat";
    /** 工具运维：/api/v1/tools */
    String TOOLS    = V1 + "/tools";
    /** 微信接入：/api/v1/wechat */
    String WECHAT   = V1 + "/wechat";
    /** 桌面端：/api/v1/desktop */
    String DESKTOP  = V1 + "/desktop";
    /** 语音合成：/api/v1/tts */
    String TTS      = V1 + "/tts";
    /** 异步任务：/api/v1/task */
    String TASK     = V1 + "/task";
    /** 邮件：/api/v1/mail */
    String MAIL     = V1 + "/mail";
}
