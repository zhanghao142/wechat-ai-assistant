package com.wechatai.ai.controller;

import com.wechatai.ai.service.ReasonNarrateService;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 桌面思维链叙述 API（同步 + 流式）。
 */
@RestController
@RequestMapping(ApiPrefix.DESKTOP)
@RequiredArgsConstructor
public class DesktopReasonController {

    private static final Logger log = LoggerFactory.getLogger(DesktopReasonController.class);
    private static final long SSE_TIMEOUT_MS = 90_000L;

    private final ReasonNarrateService reasonNarrateService;

    /**
     * POST /api/v1/desktop/reason/narrate
     * body: { userText, phase, eventText, recentLines?, factsHint? }
     */
    @PostMapping("/reason/narrate")
    public Result<Map<String, String>> narrate(@RequestBody NarrateReq req) {
        if (req == null) {
            return Result.error(400, "参数为空");
        }
        List<String> recent = normalizeRecent(req.getRecentLines());
        String line = reasonNarrateService.narrate(
                req.getUserText(),
                req.getPhase(),
                req.getEventText(),
                recent,
                req.getFactsHint());
        return Result.success(Map.of("text", line != null ? line : ""));
    }

    /**
     * POST /api/v1/desktop/reason/narrate-stream
     * SSE: delta {text} → done {text: 清洗后全文}
     */
    @PostMapping(value = "/reason/narrate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter narrateStream(@RequestBody NarrateReq req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (req == null) {
            CompletableFuture.runAsync(() -> sendError(emitter, "参数为空"));
            return emitter;
        }
        List<String> recent = normalizeRecent(req.getRecentLines());
        String userText = req.getUserText();
        String phase = req.getPhase();
        String eventText = req.getEventText();
        String factsHint = req.getFactsHint();

        CompletableFuture.runAsync(() -> {
            try {
                String cleaned = reasonNarrateService.narrateStream(
                        userText, phase, eventText, recent, factsHint,
                        chunk -> {
                            synchronized (emitter) {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("delta")
                                            .data(Map.of("text", chunk)));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("text", cleaned != null ? cleaned : "");
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("done").data(done));
                    emitter.complete();
                }
            } catch (Exception e) {
                log.warn("【思维链流式】失败: {}", e.getMessage());
                sendError(emitter, e.getMessage() != null ? e.getMessage() : "叙述生成失败");
            }
        });
        return emitter;
    }

    private static List<String> normalizeRecent(List<String> recentLines) {
        List<String> recent = recentLines != null ? new ArrayList<>(recentLines) : new ArrayList<>();
        if (recent.size() > 6) {
            recent = recent.subList(recent.size() - 6, recent.size());
        }
        return recent;
    }

    private static void sendError(SseEmitter emitter, String message) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", message != null ? message : "未知错误")));
                emitter.complete();
            }
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already completed
            }
        }
    }

    @Data
    public static class NarrateReq {
        private String userText;
        private String phase;
        private String eventText;
        private List<String> recentLines;
        private String factsHint;
    }
}
