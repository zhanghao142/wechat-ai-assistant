package com.wechatai.wechat.message;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.wechatai.ai.ai.QwenVLService;
import com.wechatai.common.model.ConversationKey;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.service.DocumentService;
import com.wechatai.session.entity.SessionEntity;
import com.wechatai.session.service.SessionService;
import com.wechatai.wechat.connection.WechatConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 消息处理 — 文本/图片/语音/文件各类型的具体处理逻辑。
 * <p>
 * 每个方法只做"消息→语义"转换，回复发送统一委托给 {@link WechatOutboundSender}。
 */
@Slf4j
@Service
public class WechatInboundHandler {

    private final WechatConnectionManager connectionManager;
    private final WechatChatBridge chatBridge;
    private final QwenVLService qwenVLService;
    private final DocumentService documentService;
    private final SessionService sessionService;

    public WechatInboundHandler(@Lazy WechatConnectionManager connectionManager,
                                WechatChatBridge chatBridge,
                                QwenVLService qwenVLService,
                                DocumentService documentService,
                                SessionService sessionService) {
        this.connectionManager = connectionManager;
        this.chatBridge = chatBridge;
        this.qwenVLService = qwenVLService;
        this.documentService = documentService;
        this.sessionService = sessionService;
    }

    /**
     * 文本消息 → 直接走 AI 引擎。
     */
    public void handleText(String fromUser, String content) {
        if (content == null || content.isEmpty()) return;
        log.info("[微信消息] {}: {}", fromUser, content);
        chatBridge.think(fromUser, content, "TEXT");
    }

    /**
     * 图片消息 → 下载 → Qwen-VL 识别 → 拼入消息内容 → 走 AI 引擎。
     * <p>
     * 提交到用户线程池异步执行，避免 Qwen-VL 识别（1-3s）阻塞轮询线程导致丢消息。
     */
    public void handleImage(String fromUser, MessageItem item) {
        chatBridge.getExecutor(fromUser).submit(() -> {
            log.info("[微信图片] {}: 正在识别...", fromUser);
            try {
                // ① SDK 自动下载 + 解密 → byte[]
                byte[] imageBytes = connectionManager.getClient().downloadImageFromMessageItem(item);

                // ② Qwen-VL 多模态识别
                String description = qwenVLService.recognize(imageBytes);

                // ③ 拼成消息内容
                String content = "[用户发送了一张图片，图片内容：" + description + "]";
                log.info("[微信图片] {}: 识别结果 -> {}", fromUser, description);

                // ④ 已在用户线程内，直接调用 thinkOnCurrentThread
                chatBridge.thinkOnCurrentThread(fromUser, content, "IMAGE");

            } catch (Exception e) {
                log.error("处理图片失败 fromUser={}", fromUser, e);
                chatBridge.thinkOnCurrentThread(fromUser, "[用户发送了一张图片，图片识别失败]", "IMAGE");
            }
        });
    }

    /**
     * 语音消息 → 直接取微信转写文字 → 拼入消息内容 → 走 AI 引擎。
     * <p>
     * SDK 2.3.3 的 VoiceItem 自带 getText()（微信服务端已自动转好文字），无需额外 ASR 调用。
     */
    public void handleVoice(String fromUser, MessageItem item) {
        String text = item.getVoice_item().getText();
        if (text == null || text.isEmpty()) {
            log.info("[微信语音] {}: 转写文字为空", fromUser);
            chatBridge.think(fromUser, "[用户发送了一条语音，语音内容无法识别]", "VOICE");
            return;
        }
        String content = "[用户发送了一条语音，语音内容：" + text + "]";
        log.info("[微信语音] {}: {} -> {}", fromUser, text, content);
        chatBridge.think(fromUser, content, "VOICE");
    }

    /**
     * 文件消息 → 下载 → 上传并同步解析 → 走 AI 引擎回复用户。
     * <p>
     * 在用户线程池内执行下载+上传+AI 调用。
     */
    public void handleFile(String fromUser, MessageItem item) {
        chatBridge.getExecutor(fromUser).submit(() -> {
            try {
                String fileName = item.getFile_item().getFile_name();
                log.info("[微信文件] {}: {} 正在下载...", fromUser, fileName);

                byte[] fileBytes = connectionManager.getClient().downloadFileFromMessageItem(item);
                SessionEntity session = sessionService.getOrCreateActiveSession(fromUser);
                DocumentVO vo = documentService.upload(fromUser, session.getSessionId(), fileName, fileBytes);

                String status = vo.getStatus().name();
                String fileId = vo.getFileId();
                String msg;
                if ("PARSED".equals(status)) {
                    msg = "我上传了一个文件「" + fileName + "」(fileId=" + fileId + ")，已解析完成。你可以直接问我关于文档的问题，我会自动检索相关内容";
                } else if ("FAILED".equals(status)) {
                    msg = "我上传了一个文件「" + fileName + "」(fileId=" + fileId + ")，但解析失败了";
                } else {
                    msg = "我上传了一个文件「" + fileName + "」(fileId=" + fileId + ")";
                }

                // 已在用户线程内，直接调用 thinkOnCurrentThread
                chatBridge.thinkOnCurrentThread(fromUser, msg, "FILE");
                log.info("[微信文件] {}: {} 上传完成并走 AI，状态={}", fromUser, fileName, status);
            } catch (Exception e) {
                log.error("[微信文件] 处理失败 fromUser={}", fromUser, e);
                try {
                    connectionManager.getClient().sendText(fromUser, "收到文件但处理失败，请稍后重试");
                } catch (Exception ignored) {}
            }
        });
    }

    // ========================================================================
    // 多账号适配方法 — 接受 conversationKey，提取 fromUserId 后委托给现有方法
    // ========================================================================

    public void handleTextByKey(String conversationKey, String content) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        handleText(fromUser, content);
    }

    public void handleImageByKey(String conversationKey, MessageItem item) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        handleImage(fromUser, item);
    }

    public void handleVoiceByKey(String conversationKey, MessageItem item) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        handleVoice(fromUser, item);
    }

    public void handleFileByKey(String conversationKey, MessageItem item) {
        String fromUser = ConversationKey.fromUserId(conversationKey);
        handleFile(fromUser, item);
    }
}
