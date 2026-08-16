package com.wechatai.ai.controller;

import com.wechatai.ai.ai.TtsService;
import com.wechatai.ai.ai.VoiceConfigManager;
import com.wechatai.common.constant.ApiPrefix;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桌面端 TTS — 复用 {@link TtsService}（CosyVoice），返回 MP3 字节流。
 * <p>
 * 微信出站仍走 {@code WechatOutboundSender}；本接口仅供桌面播放/重播。
 */
@RestController
@RequestMapping(ApiPrefix.TTS)
@RequiredArgsConstructor
public class TtsController {

    private static final Logger log = LoggerFactory.getLogger(TtsController.class);
    private static final int MAX_TEXT_CHARS = 800;

    private final TtsService ttsService;

    /**
     * POST /api/v1/tts/synthesize
     * body: { "text": "...", "voice": "longanhuan?" }
     */
    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest req) {
        if (req == null || req.getText() == null || req.getText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String text = req.getText().trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }

        String voice = req.getVoice();
        if (voice != null && !voice.isBlank() && !VoiceConfigManager.VALID_VOICES.contains(voice.trim())) {
            log.warn("[TTS API] 非法音色 {}，回退默认", voice);
            voice = null;
        }

        byte[] audio = ttsService.synthesize(text, voice);
        if (audio == null || audio.length == 0) {
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(audio);
    }

    @Data
    public static class TtsRequest {
        private String text;
        private String voice;
        private String userId;
    }
}
