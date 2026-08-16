package com.wechatai.ai.ai;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 语音合成服务 — 阿里云百炼 CosyVoice TTS。
 * <p>
 * 使用 DashScope SDK v2 WebSocket 协议，合成文本为 MP3 音频。
 * 通过 {@code new SpeechSynthesizer(param, null).call(text)} 同步调用。
 * <p>
 * 依赖配置：
 * <ul>
 *   <li>{@code tts.api-key} — 阿里云百炼 API Key（需开通 WebSocket 权限）</li>
 *   <li>{@code tts.websocket-url} — WebSocket 端点</li>
 *   <li>{@code tts.model} — 模型名，如 cosyvoice-v3-plus</li>
 *   <li>{@code tts.voice} — 音色名，如 longanhuan</li>
 * </ul>
 */
@Component
public class TtsService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    @Value("${tts.api-key}")
    private String apiKey;

    @Value("${tts.websocket-url}")
    private String websocketUrl;

    @Value("${tts.model}")
    private String modelName;

    @Value("${tts.voice}")
    private String voiceName;

    /**
     * 初始化 DashScope SDK 全局配置。
     * Constants 的静态变量必须在调用 SDK 前完成设置。
     */
    @Override
    public void afterPropertiesSet() {
        Constants.apiKey = apiKey;
        Constants.baseWebsocketApiUrl = websocketUrl;
        log.info("TTS 服务初始化完成: model={}, voice={}", modelName, voiceName);
    }

    /**
     * 将文本合成为 MP3 音频数据（使用默认音色）。
     *
     * @param text 待合成的文本内容
     * @return MP3 音频字节数组，合成失败或内容为空时返回空数组
     */
    public byte[] synthesize(String text) {
        return synthesize(text, voiceName);
    }

    /**
     * 将文本合成为 MP3 音频数据（指定音色）。
     * <p>
     * v2 API: {@code new SpeechSynthesizer(param, null).call(text)} 同步返回 ByteBuffer。
     *
     * @param text  待合成的文本内容
     * @param voice 音色名称（如 longanhuan、longchen 等），为 null 时使用默认音色
     * @return MP3 音频字节数组，合成失败或内容为空时返回空数组
     */
    public byte[] synthesize(String text, String voice) {
        return synthesizeInternal(text, voice, false);
    }

    /**
     * 内部合成方法。
     *
     * @param text   待合成文本
     * @param voice  音色名称
     * @param retry  是否为重试（避免无限递归）
     */
    private byte[] synthesizeInternal(String text, String voice, boolean retry) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("TTS 合成内容为空");
            return new byte[0];
        }

        String actualVoice = (voice != null && !voice.isEmpty()) ? voice : voiceName;

        long start = System.currentTimeMillis();
        SpeechSynthesizer synthesizer = null;

        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(modelName)
                    .voice(actualVoice)
                    .format(SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS)
                    .build();

            synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audioBuffer = synthesizer.call(text);

            // SDK 在 API 返回错误时可能返回 null（而非抛异常）
            if (audioBuffer == null || audioBuffer.remaining() == 0) {
                log.warn("【TTS】SDK 返回空, voice={}", actualVoice);

                // 如果指定音色与默认音色不同且尚未重试，用默认音色重试一次
                if (!retry && !actualVoice.equals(voiceName)) {
                    log.info("【TTS】音色 {} 无效，回退到默认音色 {} 重试", actualVoice, voiceName);
                    return synthesizeInternal(text, voiceName, true);
                }
                return new byte[0];
            }

            byte[] audio = new byte[audioBuffer.remaining()];
            audioBuffer.get(audio);

            long elapsed = System.currentTimeMillis() - start;
            log.info("【TTS】合成完成 ({}ms): voice={}, {} 字符 → {} 字节",
                    elapsed, actualVoice, text.length(), audio.length);

            return audio;

        } catch (Exception e) {
            log.error("【TTS】合成失败 voice={} apiKey={} model={} exception={}: {}",
                    actualVoice,
                    apiKey != null ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..." : "null",
                    modelName,
                    e.getClass().getSimpleName(), e.getMessage());

            // 如果指定音色与默认音色不同且尚未重试，用默认音色重试一次
            if (!retry && !actualVoice.equals(voiceName)) {
                log.info("【TTS】音色 {} 异常，回退到默认音色 {} 重试", actualVoice, voiceName);
                return synthesizeInternal(text, voiceName, true);
            }
            return new byte[0];
        } finally {
            if (synthesizer != null) {
                try {
                    synthesizer.getDuplexApi().close(1000, "bye");
                } catch (Exception ignored) {
                }
            }
        }
    }
}
