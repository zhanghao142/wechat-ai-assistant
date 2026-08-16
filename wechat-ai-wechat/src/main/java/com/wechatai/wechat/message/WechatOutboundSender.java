package com.wechatai.wechat.message;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.wechatai.ai.ai.TtsService;
import com.wechatai.ai.ai.VoiceConfigManager;
import com.wechatai.common.model.ConversationKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.wechatai.wechat.connection.WechatConnectionManager;
import com.wechatai.wechat.multi.WechatClientRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息发送出口 — 统一管理 sendText / sendFile / TTS 语音 + 音色动态切换。
 * <p>
 * 所有对外发送都经过此组件：LLM 回复、错误提示、文件发送均走这里。
 * 支持 4 种音色标记的解析和三级优先级音色解析。
 */
@Slf4j
@Service
public class WechatOutboundSender {

    private final WechatConnectionManager connectionManager;
    private final TtsService ttsService;
    private final VoiceConfigManager voiceConfigManager;
    private final String voiceStoragePath;
    private final String defaultVoice;

    /** 多账号模式下的 client 注册中心（单账号模式为 null） */
    @Autowired(required = false)
    private WechatClientRegistry clientRegistry;

    /** 短期音色切换：key=微信用户ID，value=当前对话的音色名（进程重启后失效） */
    private final Map<String, String> shortTermVoices = new ConcurrentHashMap<>();

    // === 音色标记正则 ===
    private static final Pattern VOICE_SET_PATTERN    = Pattern.compile("\\[VOICE_SET:(\\w+)\\]");
    private static final Pattern VOICE_SWITCH_PATTERN = Pattern.compile("\\[VOICE_SWITCH:(\\w+)\\]");
    private static final Pattern VOICE_ONE_SHOT_PATTERN = Pattern.compile("\\[VOICE:(\\w+)\\]");
    private static final Pattern VOICE_PATTERN        = Pattern.compile("\\[VOICE\\]");

    public WechatOutboundSender(@Lazy WechatConnectionManager connectionManager,
                                TtsService ttsService,
                                VoiceConfigManager voiceConfigManager,
                                @Value("${tts.storage-path:./TTS}") String voiceStoragePath,
                                @Value("${tts.voice}") String defaultVoice) {
        this.connectionManager = connectionManager;
        this.ttsService = ttsService;
        this.voiceConfigManager = voiceConfigManager;
        this.voiceStoragePath = voiceStoragePath;
        this.defaultVoice = defaultVoice;
    }

    /**
     * 获取负责发送给指定用户的 ILinkClient — 多账号模式查 userId→client 映射，单账号走 ConnectionManager。
     */
    private ILinkClient getActiveClientForUser(String userId) {
        if (clientRegistry != null) {
            String clientId = clientRegistry.findClientForUser(userId);
            if (clientId != null) {
                return clientRegistry.getClient(clientId);
            }
            if (clientRegistry.count() > 0) {
                return clientRegistry.getClient(clientRegistry.listClientIds().get(0));
            }
        }
        return connectionManager.getClient();
    }

    /**
     * 发送纯文本消息。
     */
    public void sendText(String fromUser, String text) {
        try {
            ILinkClient client = getActiveClientForUser(fromUser);
            client.sendText(fromUser, text);
            log.info("[微信回复] {}: {}", fromUser, text);
        } catch (Exception e) {
            log.error("发送文本消息失败 fromUser={}", fromUser, e);
        }
    }

    /**
     * 发送文件（MP3 等二进制内容）。
     */
    public void sendFile(String fromUser, byte[] data, String fileName, String ext) {
        try {
            ILinkClient client = getActiveClientForUser(fromUser);
            client.sendFile(fromUser, data, fileName, ext);
        } catch (Exception e) {
            log.error("发送文件失败 fromUser={}", fromUser, e);
        }
    }

    /**
     * 发送图片 — 从本地文件路径读取后通过 sendImage 发送为微信行内图片。
     */
    public void sendImage(String fromUser, String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                log.warn("图片文件不存在，跳过发送: {}", filePath);
                return;
            }
            byte[] data = Files.readAllBytes(path);
            String fileName = path.getFileName().toString();
            String ext = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1) : "jpeg";
            ext = ext.equals("jpg") ? "jpeg" : ext;

            ILinkClient client = getActiveClientForUser(fromUser);
            client.sendImage(fromUser, data, fileName, ext);
            log.info("[微信图片] {}: 已发送行内图片 {}", fromUser, fileName);
        } catch (Exception e) {
            log.error("发送图片失败 fromUser={}, path={}", fromUser, filePath, e);
        }
    }

    /**
     * 发送错误提示给用户。
     */
    public void sendError(String fromUser) {
        try {
            ILinkClient client = getActiveClientForUser(fromUser);
            client.sendText(fromUser, "抱歉，处理出错了，请稍后再试");
        } catch (Exception ex) {
            log.error("发送错误消息失败", ex);
        }
    }

    // ========================================================================
    // 多账号发送方法 — 接受 conversationKey（clientId:fromUserId），通过 Registry 路由
    // ========================================================================

    /** 获取负责发送的 ILinkClient：多账号走 Registry，单账号走 ConnectionManager */
    private ILinkClient resolveClient(String conversationKey) {
        if (clientRegistry != null) {
            String clientId = ConversationKey.clientId(conversationKey);
            return clientRegistry.getClient(clientId);
        }
        return connectionManager.getClient();
    }

    /** 从 conversationKey 解析目标用户 ID */
    private String resolveUser(String conversationKey) {
        if (clientRegistry != null) {
            return ConversationKey.fromUserId(conversationKey);
        }
        return conversationKey; // 单账号模式 key 就是 fromUser
    }

    public void sendTextByKey(String conversationKey, String text) {
        try {
            ILinkClient client = resolveClient(conversationKey);
            String toUser = resolveUser(conversationKey);
            client.sendText(toUser, text);
            log.info("[微信回复] {}: {}", conversationKey, text);
        } catch (Exception e) {
            log.error("发送文本消息失败 key={}", conversationKey, e);
        }
    }

    public void sendErrorByKey(String conversationKey) {
        try {
            ILinkClient client = resolveClient(conversationKey);
            String toUser = resolveUser(conversationKey);
            client.sendText(toUser, "抱歉，处理出错了，请稍后再试");
        } catch (Exception ex) {
            log.error("发送错误消息失败 key={}", conversationKey, ex);
        }
    }

    public void sendReplyByKey(String conversationKey, String reply) {
        String toUser = resolveUser(conversationKey);
        sendReply(toUser, reply);
    }

    public void sendImageByKey(String conversationKey, String filePath) {
        String toUser = resolveUser(conversationKey);
        sendImage(toUser, filePath);
    }

    private static final Pattern FILE_PATTERN = Pattern.compile("\\[FILE:([^\\]]+)\\]");

    /**
     * 智能回复 — 解析全部 4 种音色标记，支持动态切换 + 持久化。
     * <p>
     * 标记类型：
     * <ul>
     *   <li>{@code [VOICE]} — 语音输出，使用当前音色</li>
     *   <li>{@code [VOICE:xxx]} — 单次指定音色 + 语音输出</li>
     *   <li>{@code [VOICE_SWITCH:xxx]} — 短期切换音色（进程内有效）</li>
     *   <li>{@code [VOICE_SET:xxx]} — 永久切换音色（持久化到文件）</li>
     * </ul>
     * 音色优先级：单次 [VOICE:xxx] > 短期切换 > 持久偏好 > 配置默认值
     */
    public void sendReply(String fromUser, String reply) {
        // ─────────── 音色标记解析 ───────────
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
            // 防御：非法音色回退默认
            if (!VoiceConfigManager.VALID_VOICES.contains(resolvedVoice)) {
                log.warn("【音色】用户 {} 音色 {} 不在有效列表中，回退默认 {}",
                        fromUser, resolvedVoice, defaultVoice);
                resolvedVoice = defaultVoice;
            }
            log.info("【音色】用户 {} 解析音色: oneTime={}, shortTerm={}, persistent={}, default={} → {}",
                    fromUser, oneTimeVoice, shortTermVoices.get(fromUser),
                    voiceConfigManager.getVoice(fromUser), defaultVoice, resolvedVoice);

            sendVoice(fromUser, reply, resolvedVoice);
        } else if (!reply.isEmpty()) {
            sendText(fromUser, reply);
        }
    }

    /**
     * TTS 合成 → 存本地 MP3 文件 → 发送文件，失败时回退文字。
     */
    private void sendVoice(String fromUser, String text, String voice) {
        try {
            byte[] audio = ttsService.synthesize(text, voice);
            if (audio == null || audio.length == 0) {
                log.warn("【TTS】合成返回空 voice={}, 回退文字回复", voice);
                sendText(fromUser, text);
                return;
            }
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path voiceDir = Paths.get(voiceStoragePath, dateStr);
            Files.createDirectories(voiceDir);
            String mp3Name = "语音回复_" + timeStr + ".mp3";
            Path mp3Path = voiceDir.resolve(mp3Name);
            Files.write(mp3Path, audio);

            sendFile(fromUser, audio, mp3Name, "mp3");
            log.info("[TTS 语音] {}: 已发送 ({}), voice={}, 本地: {}",
                    fromUser, mp3Name, voice, mp3Path);
        } catch (Exception e) {
            log.error("【TTS】合成/发送失败 voice={}, 回退文字回复", voice, e);
            sendText(fromUser, text);
        }
    }
}
