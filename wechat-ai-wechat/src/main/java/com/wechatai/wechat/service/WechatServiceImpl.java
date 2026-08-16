package com.wechatai.wechat.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.wechatai.ai.ai.QwenVLService;
import com.wechatai.ai.ai.TtsService;
import com.wechatai.ai.ai.VoiceConfigManager;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.service.DocumentService;
import com.wechatai.session.entity.SessionEntity;
import com.wechatai.session.service.SessionService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信接入实现 — 使用 iLink Java SDK 直连微信。
 * <p>
 * 启动时自动初始化 SDK 并扫码登录。
 * 消息通过 SDK 轮询拉取，按 item.type 分发：
 * <ul>
 *   <li>type=1 文本 → 直接走 AI 引擎</li>
 *   <li>type=2 图片 → Qwen-VL 识别 → 拼入消息内容 → 走 AI 引擎</li>
 *   <li>type=3 语音 → 直接取微信转写文字 → 走 AI 引擎</li>
 *   <li>type=4 文件 → 下载并同步解析 → 回复用户</li>
 * </ul>
 * 同时保留 REST API 路径（WechatController /api/v1/wechat/send）供外部调用。
 */
@Slf4j
// @Service — 已由 WechatConnectionManager + WechatChatBridge + WechatOutboundSender 替代
public class WechatServiceImpl {

    private final StateGraph<ChatGraphState> chatGraph;
    private final QwenVLService qwenVLService;
    private final TtsService ttsService;
    private final VoiceConfigManager voiceConfigManager;
    private final DocumentService documentService;
    private final SessionService sessionService;

    @Value("${tts.storage-path:D:\\解析文件\\voice}")
    private String voiceStoragePath;

    @Value("${tts.voice}")
    private String defaultVoice;

    private ILinkClient client;

    /** 按用户缓存对话历史，key=微信用户ID，value=消息历史列表 */
    private final Map<String, List<Map<String, Object>>> userContexts = new ConcurrentHashMap<>();

    /** 短期音色切换：key=微信用户ID，value=当前对话的音色名（进程重启后失效） */
    private final Map<String, String> shortTermVoices = new ConcurrentHashMap<>();

    /** 每个用户一个单线程执行器，保证同用户消息按顺序处理 */
    private final Map<String, ExecutorService> userExecutors = new ConcurrentHashMap<>();

    // === 音色标记正则 ===
    /** 匹配 [VOICE_SET:xxx] — 永久切换音色 */
    private static final Pattern VOICE_SET_PATTERN = Pattern.compile("\\[VOICE_SET:(\\w+)\\]");
    /** 匹配 [VOICE_SWITCH:xxx] — 短期切换音色 */
    private static final Pattern VOICE_SWITCH_PATTERN = Pattern.compile("\\[VOICE_SWITCH:(\\w+)\\]");
    /** 匹配 [VOICE:xxx] — 单次指定音色 */
    private static final Pattern VOICE_ONE_SHOT_PATTERN = Pattern.compile("\\[VOICE:(\\w+)\\]");
    /** 匹配 [VOICE] — 使用当前音色输出语音 */
    private static final Pattern VOICE_PATTERN = Pattern.compile("\\[VOICE\\]");

    private ExecutorService getExecutor(String userId) {
        return userExecutors.computeIfAbsent(userId, k ->
                Executors.newSingleThreadExecutor(r -> new Thread(r, "ai-" + userId.substring(0, Math.min(8, userId.length())))));
    }

    public WechatServiceImpl(StateGraph<ChatGraphState> chatGraph, QwenVLService qwenVLService,
                              TtsService ttsService, VoiceConfigManager voiceConfigManager,
                              DocumentService documentService, SessionService sessionService) {
        this.chatGraph = chatGraph;
        this.qwenVLService = qwenVLService;
        this.ttsService = ttsService;
        this.voiceConfigManager = voiceConfigManager;
        this.documentService = documentService;
        this.sessionService = sessionService;
    }

    @PostConstruct
    public void init() {
        Thread initThread = new Thread(() -> {
            log.info("【微信SDK】初始化线程启动...");
            try {
                log.info("【微信SDK】构建 ILinkClient...");
                client = ILinkClient.builder()
                        .build();
                log.info("【微信SDK】ILinkClient 构建成功");

                // 打印二维码链接，手机微信扫码确认
                log.info("【微信SDK】正在获取登录二维码...");
                String qrcodeUrl = client.executeLogin();
                log.info("=== 请扫码登录微信 ===");
                log.info("二维码链接: {}", qrcodeUrl);
                log.info("========================================");

                // 等待登录完成
                log.info("【微信SDK】等待手机扫码确认...");
                client.getLoginFuture().get();
                log.info("✅ 微信登录成功");

                // 启动轮询收消息
                pollMessages();

            } catch (Exception e) {
                log.error("【微信SDK】初始化失败(Exception): {}", e.getMessage(), e);
            } catch (Throwable t) {
                log.error("【微信SDK】初始化失败(Throwable): {}", t.getClass().getName(), t);
            }
        }, "wechat-ilink-init");
        initThread.setDaemon(false);
        initThread.start();
        log.info("【微信SDK】初始化线程已启动, id={}", initThread.getId());
    }

    private void pollMessages() {
        new Thread(() -> {
            while (true) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null && !messages.isEmpty()) {
                        handleMessages(messages);
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    log.error("轮询消息失败", e);
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }, "wechat-ilink-poll").start();
    }

    /**
     * 遍历消息列表，按 item.type 分发处理。
     * ⚠️ 必须用 item.type 判断消息类型，不能用顶层的 message_type 字段。
     */
    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String fromUser = msg.getFrom_user_id();
            if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
                continue;
            }
            for (MessageItem item : msg.getItem_list()) {
                handleItem(fromUser, item);
            }
        }
    }

    /**
     * 单条消息处理分发：
     * type=1 文本，type=2 图片，type=4 文件，其余忽略。
     */
    private void handleItem(String fromUser, MessageItem item) {
        try {
            switch (item.getType()) {
                case 1 -> {
                    if (item.getText_item() != null) {
                        handleTextMessage(fromUser, item.getText_item().getText());
                    }
                }
                case 2 -> {
                    if (item.getImage_item() != null) {
                        handleImageMessage(fromUser, item);
                    }
                }
                case 3 -> {
                    if (item.getVoice_item() != null) {
                        handleVoiceMessage(fromUser, item);
                    }
                }
                case 4 -> {
                    // 文件消息：下载 → 上传（同步解析）→ 回复用户
                    if (item.getFile_item() != null) {
                        handleFileMessage(fromUser, item);
                    }
                }
                default -> log.debug("未知消息类型: type={}", item.getType());
            }
        } catch (Exception e) {
            log.error("处理消息失败 fromUser={}", fromUser, e);
            try {
                client.sendText(fromUser, "抱歉，处理出错了，请稍后再试");
            } catch (Exception ignored) {}
        }
    }

    /**
     * 文本消息 → 直接走 AI 引擎。
     */
    private void handleTextMessage(String fromUser, String content) {
        if (content == null || content.isEmpty()) return;
        log.info("[微信消息] {}: {}", fromUser, content);
        callAiEngine(fromUser, content, "TEXT");
    }

    /**
     * 图片消息 → 下载 → Qwen-VL 识别 → 拼入消息内容 → 走 AI 引擎。
     * <p>
     * 同步等待识别结果（约 1-3s），识别后 LLM 直接在上下文中看到图片内容。
     */
    private void handleImageMessage(String fromUser, MessageItem item) {
        log.info("[微信图片] {}: 正在识别...", fromUser);
        try {
            // ① SDK 自动下载 + 解密 → byte[]
            byte[] imageBytes = client.downloadImageFromMessageItem(item);

            // ② Qwen-VL 多模态识别
            String description = qwenVLService.recognize(imageBytes);

            // ③ 拼成消息内容
            String content = "[用户发送了一张图片，图片内容：" + description + "]";
            log.info("[微信图片] {}: 识别结果 -> {}", fromUser, description);

            // ④ 走 AI 引擎（LLM 直接能看到图片内容）
            callAiEngine(fromUser, content, "IMAGE");

        } catch (Exception e) {
            log.error("处理图片失败 fromUser={}", fromUser, e);
            // 识别失败也给个占位内容
            callAiEngine(fromUser, "[用户发送了一张图片，图片识别失败]", "IMAGE");
        }
    }

    /**
     * 语音消息 → 直接取微信转写文字 → 拼入消息内容 → 走 AI 引擎。
     * <p>
     * SDK 2.3.3 的 VoiceItem 自带 getText()（微信服务端已自动转好文字），无需额外 ASR 调用。
     */
    private void handleVoiceMessage(String fromUser, MessageItem item) {
        String text = item.getVoice_item().getText();
        if (text == null || text.isEmpty()) {
            log.info("[微信语音] {}: 转写文字为空", fromUser);
            callAiEngine(fromUser, "[用户发送了一条语音，语音内容无法识别]", "VOICE");
            return;
        }
        String content = "[用户发送了一条语音，语音内容：" + text + "]";
        log.info("[微信语音] {}: {} -> {}", fromUser, text, content);
        callAiEngine(fromUser, content, "VOICE");
    }

    /**
     * 文件消息 → 下载 → 上传并同步解析 → 走 AI 引擎回复用户。
     * <p>
     * 把"上传文件"当作一条用户消息走 AI 引擎，LLM 自然就知道有文件了，
     * 文档内容通过 RAG 自动检索注入 LLM 上下文，无需手动搜索。
     */
    private void handleFileMessage(String fromUser, MessageItem item) {
        getExecutor(fromUser).submit(() -> {
            try {
                String fileName = item.getFile_item().getFile_name();
                log.info("[微信文件] {}: {} 正在下载...", fromUser, fileName);

                byte[] fileBytes = client.downloadFileFromMessageItem(item);
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

                // 走 AI 引擎（RAG 自动检索文档内容，LLM 直接作答）
                callAiEngine(fromUser, msg, "FILE");
                log.info("[微信文件] {}: {} 上传完成并走 AI，状态={}", fromUser, fileName, status);
            } catch (Exception e) {
                log.error("[微信文件] 处理失败 fromUser={}", fromUser, e);
                try {
                    client.sendText(fromUser, "收到文件但处理失败，请稍后重试");
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 统一 AI 引擎调用入口。
     * <p>
     * 将用户消息传入 LangGraph 图编排，取回回复并发送给用户。
     * 同时维护多轮对话记忆（userContexts）。
     */
    private void callAiEngine(String fromUser, String content, String messageType) {
        getExecutor(fromUser).submit(() -> {
            try {
                Map<String, Object> initData = new HashMap<>();
                initData.put(ChatGraphState.KEY_USER_ID, fromUser);
                initData.put(ChatGraphState.KEY_USER_MESSAGE, content);
                initData.put(ChatGraphState.KEY_MESSAGE_TYPE, messageType);
                initData.put("messageHistory", userContexts.get(fromUser));

                ChatGraphState state = chatGraph.compile()
                        .invoke(initData)
                        .orElseThrow(() -> new RuntimeException("图执行无返回"));

                Object updatedHistory = state.data().get("messageHistory");
                if (updatedHistory instanceof List) {
                    userContexts.put(fromUser, (List<Map<String, Object>>) updatedHistory);
                }

                String reply = state.getReply();
                log.info("【LLM原始回复】用户={} 长度={} 内容={}", fromUser,
                        reply != null ? reply.length() : 0, reply);
                if (reply != null && !reply.isEmpty()) {
                    // ─────────── 音色标记解析 ───────────
                    // 优先级：单次 [VOICE:xxx] > 短期 [VOICE_SWITCH:xxx] > 持久 > 默认
                    String oneTimeVoice = null;
                    boolean useVoice = false;

                    // ① [VOICE_SET:xxx] — 永久切换，持久化到文件
                    Matcher setMatcher = VOICE_SET_PATTERN.matcher(reply);
                    if (setMatcher.find()) {
                        String persistVoice = setMatcher.group(1);
                        voiceConfigManager.setVoice(fromUser, persistVoice);
                        shortTermVoices.put(fromUser, persistVoice);
                        reply = setMatcher.replaceAll("");
                        log.info("【音色】用户 {} 永久切换 → {}", fromUser, persistVoice);
                    }

                    // ② [VOICE_SWITCH:xxx] — 短期切换，仅当前对话有效
                    Matcher switchMatcher = VOICE_SWITCH_PATTERN.matcher(reply);
                    if (switchMatcher.find()) {
                        String shortVoice = switchMatcher.group(1);
                        shortTermVoices.put(fromUser, shortVoice);
                        reply = switchMatcher.replaceAll("");
                        log.info("【音色】用户 {} 短期切换 → {}", fromUser, shortVoice);
                    }

                    // ③ [VOICE:xxx] — 单次指定音色 + 语音输出
                    Matcher oneShotMatcher = VOICE_ONE_SHOT_PATTERN.matcher(reply);
                    if (oneShotMatcher.find()) {
                        oneTimeVoice = oneShotMatcher.group(1);
                        useVoice = true;
                        reply = oneShotMatcher.replaceAll("");
                        log.info("【音色】用户 {} 单次指定 → {}", fromUser, oneTimeVoice);
                    }

                    // ④ [VOICE] — 语音输出（使用当前音色）
                    Matcher voiceMatcher = VOICE_PATTERN.matcher(reply);
                    if (voiceMatcher.find()) {
                        useVoice = true;
                        reply = voiceMatcher.replaceAll("");
                    }

                    // 清理多余空白
                    reply = reply.replaceAll("\\n{3,}", "\n\n").trim();

                    if (useVoice && !reply.isEmpty()) {
                        // 解析最终音色：单次 > 短期 > 持久 > 默认
                        String resolvedVoice = oneTimeVoice;
                        if (resolvedVoice == null) {
                            resolvedVoice = shortTermVoices.get(fromUser);
                        }
                        if (resolvedVoice == null) {
                            resolvedVoice = voiceConfigManager.getVoice(fromUser);
                        }
                        if (resolvedVoice == null) {
                            resolvedVoice = defaultVoice;
                        }
                        // 防御：非法音色回退默认（处理短期缓存中残留的无效值）
                        if (!VoiceConfigManager.VALID_VOICES.contains(resolvedVoice)) {
                            log.warn("【音色】用户 {} 音色 {} 不在有效列表中，回退默认 {}",
                                    fromUser, resolvedVoice, defaultVoice);
                            resolvedVoice = defaultVoice;
                        }
                        log.info("【音色】用户 {} 解析音色: oneTime={}, shortTerm={}, persistent={}, default={} → {}",
                                fromUser, oneTimeVoice, shortTermVoices.get(fromUser),
                                voiceConfigManager.getVoice(fromUser), defaultVoice, resolvedVoice);

                        // TTS 合成 → 保存本地 → 发送 MP3
                        byte[] audio = ttsService.synthesize(reply, resolvedVoice);
                        if (audio != null && audio.length > 0) {
                            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                            Path voiceDir = Paths.get(voiceStoragePath, dateStr);
                            Files.createDirectories(voiceDir);
                            String mp3Name = "语音回复_" + timeStr + ".mp3";
                            Path mp3Path = voiceDir.resolve(mp3Name);
                            Files.write(mp3Path, audio);

                            client.sendFile(fromUser, audio, mp3Name, "mp3");
                            log.info("[TTS 语音] {}: 已发送 ({}), 本地: {}",
                                    fromUser, mp3Name, mp3Path);
                        } else {
                            // TTS 失败时回退为文字消息，避免用户收不到任何回复
                            log.warn("【TTS】合成返回空, 回退为文字回复");
                            client.sendText(fromUser, reply);
                        }
                    } else if (!reply.isEmpty()) {
                        client.sendText(fromUser, reply);
                    }
                    log.info("[微信回复] {}: {}", fromUser, reply);
                }

            } catch (Exception e) {
                log.error("AI 处理失败 fromUser={}", fromUser, e);
                try {
                    client.sendText(fromUser, "抱歉，处理出错了，请稍后再试");
                } catch (Exception ex) {
                    log.error("发送错误消息失败", ex);
                }
            }
        });
    }
}
