package com.wechatai.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatai.agent.AgentResult;
import com.wechatai.ai.agent.AgentGraphRunner;
import com.wechatai.ai.agent.ParallelAgentBatchService;
import com.wechatai.ai.agent.ParallelAgentBatchService.ConfirmItem;
import com.wechatai.ai.agent.ParallelAgentBatchService.ResumeOutcome;
import com.wechatai.ai.ai.QwenVLService;
import com.wechatai.ai.graph.GraphInterruptHandler;
import com.wechatai.ai.model.request.ChatSendReq;
import com.wechatai.ai.model.vo.ChatResponseVO;
import com.wechatai.ai.model.vo.HistoryMessageVO;
import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.ai.service.ChatService;
import com.wechatai.ai.service.DesktopProgressEmitter;
import com.wechatai.ai.state.ChatGraphState;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.enums.MessageType;
import com.wechatai.common.model.Result;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 对话核心 REST 接口（文档第 5 章）。
 */
@RestController
@RequestMapping(ApiPrefix.CHAT)
@RequiredArgsConstructor
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024L;
    private static final long MAX_DOC_BYTES = 50 * 1024 * 1024L;
    /** 桌面流式：完整结果就绪后再分片推送的超时 */
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final int STREAM_CHUNK_CHARS = 2;
    private static final long STREAM_CHUNK_DELAY_MS = 28L;
    private static final Pattern USER_CAPTION = Pattern.compile("用户说明：\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern FILE_NAME_IN_CONTENT = Pattern.compile("「([^」]+)」");
    private static final Pattern SAFE_MEDIA_NAME = Pattern.compile("^img_[a-f0-9\\-]+\\.(png|jpg|jpeg|gif|webp)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_MARKER = Pattern.compile("\\[FILE:([^\\]]+)\\]");
    private static final Pattern SAFE_SHOT_NAME = Pattern.compile(
            "^screenshot_[a-fA-F0-9]{8}\\.(png|jpe?g|webp)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_DATE_DIR = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final StateGraph<ChatGraphState> chatGraph;
    private final ChatService chatService;
    private final QwenVLService qwenVLService;
    private final DocumentService documentService;
    private final AgentGraphRunner agentGraphRunner;
    private final GraphInterruptHandler interruptHandler;
    private final UsageMetricsService usageMetricsService;
    private final ParallelAgentBatchService parallelBatchService;
    private final DesktopProgressEmitter progressEmitter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** sessionId → 当前待确认的子 Agent WRITE threadId（进程内；并行批次时为队头） */
    private final Map<String, String> pendingConfirmBySession = new ConcurrentHashMap<>();

    /** 正在执行 confirmResume 的会话：期间禁止再开一轮编排（避免重复点确认雪崩） */
    private final java.util.Set<String> confirmBusySessions = ConcurrentHashMap.newKeySet();

    @Value("${wechat.storage.local-path:./upload}")
    private String storagePath;

    @Value("${browser.screenshot.save-dir:./screenshots}")
    private String screenshotDir;

    @Value("${docgen.storage-path:./AItext}")
    private String docgenDir;

    /** filesystem MCP 沙箱（创建的 txt 等落在此目录） */
    @Value("${mcp.filesystem.allowed-dir:./upload}")
    private String sandboxDir;

    /**
     * 5.1 发送消息 — 工具调用在图内自动完成，调用方一次拿到最终回复。
     * <p>
     * 若上一轮 WRITE 待确认，用户回复「确认/取消」会走续跑，不再新开一轮编排。
     */
    @PostMapping("/send")
    public Result<ChatResponseVO> send(@Valid @RequestBody ChatSendReq req) {
        ChatResponseVO busy = tryBusyConfirmResponse(req.getSessionId(), req.getUserId(), req.getMessage());
        if (busy != null) {
            return Result.success(busy);
        }
        ChatResponseVO pending = tryHandlePendingConfirm(
                req.getSessionId(), req.getUserId(), req.getMessage());
        if (pending != null) {
            return Result.success(pending);
        }
        return Result.success(runChat(
                req.getSessionId(),
                req.getUserId(),
                req.getMessage(),
                req.getMessageType() != null ? req.getMessageType() : "TEXT",
                req.getFileIds(),
                null
        ));
    }

    /**
     * 桌面流式：业务与 /send 完全相同，拿到完整最终汇总后再 SSE 分片推送。
     */
    @PostMapping(value = "/send-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(@Valid @RequestBody ChatSendReq req) {
        long acceptedAt = System.currentTimeMillis();
        log.info("[桌面SSE] 已收到 send-stream sessionId={} userId={} msgLen={}",
                req.getSessionId(), req.getUserId(),
                req.getMessage() != null ? req.getMessage().length() : 0);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            String sessionId = req.getSessionId();
            bindProgress(sessionId, emitter);
            try {
                log.info("[桌面SSE] 开始编排 sessionId={} waitMs={}",
                        sessionId, System.currentTimeMillis() - acceptedAt);
                progressEmitter.emit(sessionId, "正在思考");
                ChatResponseVO busy = tryBusyConfirmResponse(
                        sessionId, req.getUserId(), req.getMessage());
                ChatResponseVO pending = busy != null ? null : tryHandlePendingConfirm(
                        sessionId, req.getUserId(), req.getMessage());
                ChatResponseVO vo = busy != null
                        ? busy
                        : pending != null
                        ? pending
                        : runChat(
                        sessionId,
                        req.getUserId(),
                        req.getMessage(),
                        req.getMessageType() != null ? req.getMessageType() : "TEXT",
                        req.getFileIds(),
                        null
                );
                streamReply(emitter, vo);
                log.info("[桌面SSE] 完成 sessionId={} totalMs={}",
                        sessionId, System.currentTimeMillis() - acceptedAt);
            } catch (Exception e) {
                log.error("[桌面SSE] send-stream 失败 sessionId={}", sessionId, e);
                streamError(emitter, e.getMessage() != null ? e.getMessage() : "AI 处理失败");
            } finally {
                progressEmitter.unbind(sessionId);
            }
        });
        return emitter;
    }

    /**
     * 5.1b 发送图片 — 落盘 + Qwen-VL 识别后拼入用户消息，再走同一套对话编排。
     */
    @PostMapping("/image")
    public Result<ChatResponseVO> sendImage(@RequestParam("file") MultipartFile file,
                                            @RequestParam String sessionId,
                                            @RequestParam String userId,
                                            @RequestParam(required = false) String message) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "图片不能为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return Result.error(400, "图片不能超过 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.error(400, "仅支持图片文件");
        }
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return Result.error(400, "sessionId 与 userId 不能为空");
        }

        try {
            byte[] imageBytes = file.getBytes();
            String mediaName = saveChatImage(imageBytes, contentType, file.getOriginalFilename());
            String description = qwenVLService.recognize(imageBytes);
            log.info("[桌面图片] sessionId={} media={} 识别完成: {}", sessionId, mediaName, description);

            StringBuilder content = new StringBuilder();
            content.append("[用户发送了一张图片，图片内容：").append(description).append("]");
            if (message != null && !message.isBlank()) {
                content.append("\n用户说明：").append(message.trim());
            }

            ChatResponseVO resp = runChat(
                    sessionId, userId, content.toString(), "IMAGE",
                    List.of(mediaName), description
            );
            resp.setFileId(mediaName);
            return Result.success(resp);
        } catch (Exception e) {
            log.error("[桌面图片] 处理失败 sessionId={}", sessionId, e);
            throw new RuntimeException("图片处理失败", e);
        }
    }

    /**
     * 桌面图片流式：与 /image 相同业务，完整结果就绪后 SSE 分片推送。
     * Multipart 在请求线程读完并算完，仅推送异步。
     */
    @PostMapping(value = "/image-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendImageStream(@RequestParam("file") MultipartFile file,
                                      @RequestParam String sessionId,
                                      @RequestParam String userId,
                                      @RequestParam(required = false) String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (file == null || file.isEmpty()) {
            streamError(emitter, "图片不能为空");
            return emitter;
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            streamError(emitter, "图片不能超过 10MB");
            return emitter;
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            streamError(emitter, "仅支持图片文件");
            return emitter;
        }
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            streamError(emitter, "sessionId 与 userId 不能为空");
            return emitter;
        }

        try {
            byte[] imageBytes = file.getBytes();
            String originalFilename = file.getOriginalFilename();
            String caption = message;
            CompletableFuture.runAsync(() -> {
                try {
                    String mediaName = saveChatImage(imageBytes, contentType, originalFilename);
                    String description = qwenVLService.recognize(imageBytes);
                    log.info("[桌面图片SSE] sessionId={} media={} 识别完成: {}", sessionId, mediaName, description);

                    StringBuilder content = new StringBuilder();
                    content.append("[用户发送了一张图片，图片内容：").append(description).append("]");
                    if (caption != null && !caption.isBlank()) {
                        content.append("\n用户说明：").append(caption.trim());
                    }

                    ChatResponseVO resp = runChat(
                            sessionId, userId, content.toString(), "IMAGE",
                            List.of(mediaName), description
                    );
                    resp.setFileId(mediaName);
                    streamReply(emitter, resp);
                } catch (Exception e) {
                    log.error("[桌面图片SSE] 处理失败 sessionId={}", sessionId, e);
                    streamError(emitter, "图片处理失败");
                }
            });
        } catch (Exception e) {
            log.error("[桌面图片SSE] 读取失败 sessionId={}", sessionId, e);
            streamError(emitter, "图片读取失败");
        }
        return emitter;
    }

    /**
     * 5.1c 发送文档 — 上传并同步解析后，走同一套对话编排（对齐微信文件链路）。
     */
    @PostMapping("/file")
    public Result<ChatResponseVO> sendFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam String sessionId,
                                           @RequestParam String userId,
                                           @RequestParam(required = false) String message) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        if (file.getSize() > MAX_DOC_BYTES) {
            return Result.error(400, "文件不能超过 50MB");
        }
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return Result.error(400, "sessionId 与 userId 不能为空");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "upload.bin";
        }

        try {
            DocumentVO vo = documentService.upload(file, sessionId, userId);
            String status = vo.getStatus() != null ? vo.getStatus().name() : "UNKNOWN";
            String fileId = vo.getFileId();
            log.info("[桌面文档] sessionId={} fileId={} status={}", sessionId, fileId, status);

            StringBuilder content = new StringBuilder();
            if ("PARSED".equals(status)) {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId)
                        .append(")，已解析完成。你可以直接问我关于文档的问题，我会自动检索相关内容");
            } else if ("FAILED".equals(status)) {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId)
                        .append(")，但解析失败了");
            } else {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId).append(")");
            }
            if (message != null && !message.isBlank()) {
                content.append("\n用户说明：").append(message.trim());
            }

            ChatResponseVO resp = runChat(
                    sessionId, userId, content.toString(), "FILE",
                    List.of(fileId), null
            );
            resp.setFileId(fileId);
            resp.setFileName(fileName);
            resp.setFileStatus(status);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[桌面文档] 处理失败 sessionId={}", sessionId, e);
            throw new RuntimeException("文档处理失败", e);
        }
    }

    /**
     * 桌面文档流式：与 /file 相同业务（请求线程完成上传解析+编排），再 SSE 分片推送。
     */
    @PostMapping(value = "/file-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendFileStream(@RequestParam("file") MultipartFile file,
                                     @RequestParam String sessionId,
                                     @RequestParam String userId,
                                     @RequestParam(required = false) String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if (file == null || file.isEmpty()) {
            streamError(emitter, "文件不能为空");
            return emitter;
        }
        if (file.getSize() > MAX_DOC_BYTES) {
            streamError(emitter, "文件不能超过 50MB");
            return emitter;
        }
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            streamError(emitter, "sessionId 与 userId 不能为空");
            return emitter;
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "upload.bin";
        }

        try {
            // MultipartFile 必须在请求线程消费
            DocumentVO doc = documentService.upload(file, sessionId, userId);
            String status = doc.getStatus() != null ? doc.getStatus().name() : "UNKNOWN";
            String fileId = doc.getFileId();
            log.info("[桌面文档SSE] sessionId={} fileId={} status={}", sessionId, fileId, status);

            StringBuilder content = new StringBuilder();
            if ("PARSED".equals(status)) {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId)
                        .append(")，已解析完成。你可以直接问我关于文档的问题，我会自动检索相关内容");
            } else if ("FAILED".equals(status)) {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId)
                        .append(")，但解析失败了");
            } else {
                content.append("我上传了一个文件「").append(fileName)
                        .append("」(fileId=").append(fileId).append(")");
            }
            if (message != null && !message.isBlank()) {
                content.append("\n用户说明：").append(message.trim());
            }

            final String finalFileName = fileName;
            final String finalStatus = status;
            final String finalFileId = fileId;
            final String userContent = content.toString();

            CompletableFuture.runAsync(() -> {
                try {
                    ChatResponseVO resp = runChat(
                            sessionId, userId, userContent, "FILE",
                            List.of(finalFileId), null
                    );
                    resp.setFileId(finalFileId);
                    resp.setFileName(finalFileName);
                    resp.setFileStatus(finalStatus);
                    streamReply(emitter, resp);
                } catch (Exception e) {
                    log.error("[桌面文档SSE] 编排失败 sessionId={}", sessionId, e);
                    streamError(emitter, "文档处理失败");
                }
            });
        } catch (IllegalArgumentException e) {
            streamError(emitter, e.getMessage() != null ? e.getMessage() : "文件参数错误");
        } catch (Exception e) {
            log.error("[桌面文档SSE] 上传失败 sessionId={}", sessionId, e);
            streamError(emitter, "文档上传失败");
        }
        return emitter;
    }

    /**
     * 读取桌面聊天落盘的图片，供历史消息还原展示。
     * GET /api/v1/chat/media/{fileName}
     */
    @GetMapping("/media/{fileName}")
    public ResponseEntity<Resource> media(@PathVariable String fileName) {
        if (fileName == null || !SAFE_MEDIA_NAME.matcher(fileName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        Path path = chatImageDir().resolve(fileName).normalize();
        if (!path.startsWith(chatImageDir().normalize()) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String probe = probeContentType(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(probe))
                .body(new FileSystemResource(path));
    }

    /**
     * 浏览器截图产物 — GET /api/v1/chat/screenshots/{fileName}
     */
    @GetMapping("/screenshots/{fileName}")
    public ResponseEntity<Resource> screenshotFile(@PathVariable String fileName) {
        if (fileName == null || !SAFE_SHOT_NAME.matcher(fileName).matches()) {
            return ResponseEntity.badRequest().build();
        }
        Path root = Paths.get(screenshotDir).toAbsolutePath().normalize();
        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(MediaType.parseMediaType(probeContentType(fileName)))
                .body(new FileSystemResource(path));
    }

    /**
     * AI 生成文档产物 — GET /api/v1/chat/generated/{date}/{fileName}
     */
    @GetMapping("/generated/{date}/{fileName}")
    public ResponseEntity<Resource> generatedFile(@PathVariable String date,
                                                  @PathVariable String fileName) {
        if (date == null || !SAFE_DATE_DIR.matcher(date).matches()) {
            return ResponseEntity.badRequest().build();
        }
        if (fileName == null || fileName.isBlank() || fileName.contains("..")
                || fileName.contains("/") || fileName.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        Path root = Paths.get(docgenDir).toAbsolutePath().normalize();
        Path path = root.resolve(date).resolve(fileName).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String probe = probeContentType(fileName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(probe))
                .body(new FileSystemResource(path));
    }

    /**
     * filesystem MCP 沙箱文件 — GET /api/v1/chat/sandbox/{*relPath}
     * <p>
     * 例如 {@code /api/v1/chat/sandbox/111.txt}，供桌面端点击打开/预览。
     */
    @GetMapping("/sandbox/{*relPath}")
    public ResponseEntity<Resource> sandboxFile(@PathVariable("relPath") String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String clean = relPath.startsWith("/") ? relPath.substring(1) : relPath;
        if (clean.isBlank() || clean.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        Path root = Paths.get(sandboxDir).toAbsolutePath().normalize();
        Path path = root.resolve(clean).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String fileName = path.getFileName().toString();
        String probe = probeContentType(fileName);
        boolean inline = probe.startsWith("text/") || probe.startsWith("image/")
                || "application/json".equals(probe) || "application/pdf".equals(probe);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(probe))
                .body(new FileSystemResource(path));
    }

    /**
     * 5.2 获取历史消息（附带 mediaUrl / fileName / displayText 便于前端还原附件 UI）。
     */
    @GetMapping("/history")
    public Result<List<HistoryMessageVO>> history(@RequestParam String sessionId) {
        List<HistoryMessageVO> history = chatService.getHistory(sessionId);
        for (HistoryMessageVO m : history) {
            enrichHistoryAttachment(m);
        }
        return Result.success(history);
    }

    /**
     * 5.3 清空会话上下文（保留会话本身）。
     */
    @PostMapping("/{sessionId}/clear")
    public Result<Void> clear(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
        return Result.success();
    }

    /** 绑定桌面进度回调；并行工具线程可能并发 emit，对 emitter 加锁发送。 */
    private void bindProgress(String sessionId, SseEmitter emitter) {
        progressEmitter.bind(sessionId, text -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(Map.of("text", text)));
                } catch (Exception e) {
                    log.debug("[桌面SSE] progress 推送失败 sessionId={}: {}", sessionId, e.toString());
                }
            }
        });
    }

    /**
     * 将已算好的完整回复按短片 SSE 推送（不改变业务结果）。
     */
    private void streamReply(SseEmitter emitter, ChatResponseVO vo) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("messageId", vo.getMessageId());
            meta.put("sessionId", vo.getSessionId());
            meta.put("finishReason", vo.getFinishReason() != null ? vo.getFinishReason() : "STOP");
            meta.put("latencyMs", vo.getLatencyMs());
            if (vo.getConfirmThreadId() != null) {
                meta.put("confirmThreadId", vo.getConfirmThreadId());
            }
            if (vo.getFileId() != null) {
                meta.put("fileId", vo.getFileId());
            }
            if (vo.getFileName() != null) {
                meta.put("fileName", vo.getFileName());
            }
            if (vo.getFileStatus() != null) {
                meta.put("fileStatus", vo.getFileStatus());
            }
            if (vo.getAttachments() != null && !vo.getAttachments().isEmpty()) {
                meta.put("attachments", vo.getAttachments());
                for (Map<String, Object> a : vo.getAttachments()) {
                    if (a != null && "route".equals(a.get("type"))) {
                        meta.put("route", a);
                        break;
                    }
                }
            }
            emitter.send(SseEmitter.event().name("meta").data(meta));

            String reply = vo.getReplyMessage() != null ? vo.getReplyMessage() : "";
            int i = 0;
            while (i < reply.length()) {
                int end = Math.min(i + STREAM_CHUNK_CHARS, reply.length());
                if (end < reply.length() && Character.isHighSurrogate(reply.charAt(end - 1))) {
                    end = Math.min(end + 1, reply.length());
                }
                String chunk = reply.substring(i, end);
                emitter.send(SseEmitter.event().name("delta").data(Map.of("text", chunk)));
                i = end;
                if (i < reply.length()) {
                    Thread.sleep(STREAM_CHUNK_DELAY_MS);
                }
            }

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("finishReason", vo.getFinishReason() != null ? vo.getFinishReason() : "STOP");
            emitter.send(SseEmitter.event().name("done").data(done));
            emitter.complete();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[桌面SSE] 推送中断: {}", e.toString());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // already completed
            }
        } catch (Exception e) {
            log.error("[桌面SSE] 推送失败", e);
            streamError(emitter, e.getMessage() != null ? e.getMessage() : "流式推送失败");
        }
    }

    private void streamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                    "message", message != null ? message : "未知错误"
            )));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void enrichHistoryAttachment(HistoryMessageVO m) {
        if (m == null) return;
        MessageType type = m.getMessageType();
        List<String> ids = m.getFileIds();
        String content = m.getContent() != null ? m.getContent() : "";

        String caption = extractUserCaption(content);
        if (type == MessageType.IMAGE) {
            m.setDisplayText(caption);
            if (ids != null && !ids.isEmpty()) {
                m.setMediaUrl(ApiPrefix.CHAT + "/media/" + ids.getFirst());
            }
        } else if (type == MessageType.FILE) {
            m.setDisplayText(caption);
            String name = null;
            if (ids != null && !ids.isEmpty()) {
                try {
                    DocumentVO vo = documentService.getStatus(ids.getFirst());
                    if (vo != null) name = vo.getFileName();
                } catch (Exception ignored) {
                    // fall through to content parse
                }
            }
            if (name == null || name.isBlank()) {
                Matcher matcher = FILE_NAME_IN_CONTENT.matcher(content);
                if (matcher.find()) name = matcher.group(1);
            }
            m.setFileName(name);
        } else {
            // 兼容旧数据：无类型但正文像图片/文件描述
            if (content.contains("[用户发送了一张图片")) {
                m.setDisplayText(caption);
            } else if (content.contains("我上传了一个文件「")) {
                m.setDisplayText(caption);
                Matcher matcher = FILE_NAME_IN_CONTENT.matcher(content);
                if (matcher.find()) m.setFileName(matcher.group(1));
            }
        }
    }

    private static String extractUserCaption(String content) {
        if (content == null || content.isBlank()) return "";
        Matcher m = USER_CAPTION.matcher(content);
        if (m.find()) return m.group(1).trim();
        // 没有配文：附件消息不把内部识别长文甩给 UI
        if (content.contains("[用户发送了一张图片") || content.contains("我上传了一个文件「")) {
            return "";
        }
        return content;
    }

    private String saveChatImage(byte[] bytes, String contentType, String originalName) throws Exception {
        String ext = extensionOf(contentType, originalName);
        String mediaName = "img_" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = chatImageDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(mediaName);
        Files.write(target, bytes);
        return mediaName;
    }

    private Path chatImageDir() {
        return Paths.get(storagePath).toAbsolutePath().normalize().resolve("chat-images");
    }

    private static String extensionOf(String contentType, String originalName) {
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1) {
                String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (Set.of("png", "jpg", "jpeg", "gif", "webp").contains(ext)) {
                    return "jpeg".equals(ext) ? "jpg" : ext;
                }
            }
        }
        if (contentType == null) return "jpg";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static String probeContentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private ChatResponseVO runChat(String sessionId,
                                   String userId,
                                   String message,
                                   String messageType,
                                   List<String> fileIds,
                                   String imageDescription) {
        long startMs = System.currentTimeMillis();
        usageMetricsService.beginTurn(userId, sessionId);
        try {
            String userMsgId = "msg_u_" + UUID.randomUUID().toString().substring(0, 8);
            String assistantMsgId = "msg_a_" + UUID.randomUUID().toString().substring(0, 8);

            MessageType type;
            try {
                type = MessageType.valueOf(messageType);
            } catch (Exception e) {
                type = MessageType.TEXT;
            }
            chatService.saveUserMessage(userMsgId, sessionId, userId, message, fileIds, type);

            Map<String, Object> initData = new HashMap<>();
            initData.put(ChatGraphState.KEY_USER_ID, userId);
            initData.put(ChatGraphState.KEY_SESSION_ID, sessionId);
            initData.put(ChatGraphState.KEY_USER_MESSAGE, message);
            initData.put(ChatGraphState.KEY_MESSAGE_TYPE, messageType);
            initData.put(ChatGraphState.KEY_CHANNEL, com.wechatai.common.enums.Channel.DESKTOP.name());
            if (fileIds != null) {
                initData.put(ChatGraphState.KEY_FILE_IDS, fileIds);
            }

            ChatGraphState state = chatGraph.compile()
                    .invoke(initData)
                    .orElseThrow(() -> new RuntimeException("图执行无返回"));

            int latencyMs = (int) (System.currentTimeMillis() - startMs);

            ConfirmInfo confirm = resolveConfirm(sessionId, state);
            String reply;
            String finishReason;
            if (confirm != null) {
                pendingConfirmBySession.put(sessionId, confirm.threadId());
                reply = confirm.confirmMsg();
                finishReason = "NEED_CONFIRM";
                log.info("[桌面WRITE] sessionId={} 等待确认 threadId={} tool={} parallelQueue={}",
                        sessionId, confirm.threadId(), confirm.toolName(), confirm.fromParallelBatch());
            } else {
                pendingConfirmBySession.remove(sessionId);
                parallelBatchService.clear(sessionId);
                reply = state.getReply();
                finishReason = state.getFinishReason();
            }

            List<Map<String, Object>> attachments = buildClientAttachments(
                    state.getToolAttachments(), reply);
            String displayReply = stripFileMarkers(reply);

            chatService.saveAssistantMessage(assistantMsgId, sessionId,
                    displayReply != null ? displayReply : "", null, finishReason, latencyMs);

            ChatResponseVO resp = new ChatResponseVO();
            resp.setReplyMessage(displayReply);
            resp.setMessageId(assistantMsgId);
            resp.setSessionId(sessionId);
            resp.setFinishReason(finishReason);
            resp.setLatencyMs((long) latencyMs);
            resp.setImageDescription(imageDescription);
            resp.setAttachments(attachments.isEmpty() ? null : attachments);
            if (confirm != null) {
                resp.setConfirmThreadId(confirm.threadId());
            }
            usageMetricsService.endTurn(latencyMs);
            return resp;

        } catch (Exception e) {
            int latencyMs = (int) (System.currentTimeMillis() - startMs);
            usageMetricsService.endTurn(latencyMs);
            chatService.saveAssistantMessage(
                    "msg_a_err_" + UUID.randomUUID().toString().substring(0, 8),
                    sessionId,
                    "AI 处理失败: " + e.getMessage(),
                    null, "ERROR", latencyMs);
            throw new RuntimeException("AI 处理失败", e);
        }
    }

    /**
     * 确认续跑进行中：拦截重复「确认/取消」或任意新消息，避免再开一轮 Agent。
     */
    private ChatResponseVO tryBusyConfirmResponse(String sessionId, String userId, String message) {
        if (sessionId == null || !confirmBusySessions.contains(sessionId)) {
            return null;
        }
        long startMs = System.currentTimeMillis();
        String tip = "正在保存/执行刚才的确认操作，请稍候，不要重复发送「确认」。";
        log.info("[桌面WRITE] sessionId={} 确认执行中，忽略重复消息 msgLen={}",
                sessionId, message != null ? message.length() : 0);
        String assistantMsgId = "msg_a_" + UUID.randomUUID().toString().substring(0, 8);
        if (message != null && !message.isBlank()) {
            chatService.saveUserMessage(
                    "msg_u_" + UUID.randomUUID().toString().substring(0, 8),
                    sessionId, userId, message, null, MessageType.TEXT);
        }
        chatService.saveAssistantMessage(assistantMsgId, sessionId, tip, null, "BUSY",
                (int) (System.currentTimeMillis() - startMs));
        ChatResponseVO resp = new ChatResponseVO();
        resp.setReplyMessage(tip);
        resp.setMessageId(assistantMsgId);
        resp.setSessionId(sessionId);
        resp.setFinishReason("BUSY");
        resp.setLatencyMs(System.currentTimeMillis() - startMs);
        return resp;
    }

    /**
     * 若该会话有挂起的 WRITE 确认：
     * - 用户回复确认/取消 → 续跑子 Agent
     * - 其他内容 → 提示仍需确认/取消，不新开编排
     * 无挂起则返回 null，走正常对话。
     */
    private ChatResponseVO tryHandlePendingConfirm(String sessionId, String userId, String message) {
        // 并行批次优先：即使 pending map 丢失，也能用批次队列恢复
        ConfirmItem batchHead = parallelBatchService.peekConfirm(sessionId);
        String threadId = batchHead != null ? batchHead.threadId : pendingConfirmBySession.get(sessionId);
        if (threadId == null || message == null) return null;

        // 单次确认：Agent 侧已无挂起（已确认/汇总清掉）→ 丢掉脏 pending，避免后续句再弹确认
        if (batchHead == null && !agentGraphRunner.hasPendingConfirm(threadId)) {
            log.info("[桌面WRITE] 清理过期 pendingConfirm sessionId={} threadId={}", sessionId, threadId);
            pendingConfirmBySession.remove(sessionId);
            return null;
        }

        boolean confirmed = interruptHandler.isConfirm(message);
        boolean cancelled = interruptHandler.isCancel(message);
        if (!confirmed && !cancelled) {
            long startMs = System.currentTimeMillis();
            String tip = "请回复「确认」执行或「取消」取消该操作";
            if (batchHead != null) {
                var b = parallelBatchService.getBySession(sessionId);
                tip = ParallelAgentBatchService.formatConfirmMessage(
                        batchHead, b != null ? b.confirmQueue.size() : 1);
                tip = "请回复「确认」或「取消」。\n\n" + tip;
            }
            String assistantMsgId = "msg_a_" + UUID.randomUUID().toString().substring(0, 8);
            chatService.saveUserMessage(
                    "msg_u_" + UUID.randomUUID().toString().substring(0, 8),
                    sessionId, userId, message, null, MessageType.TEXT);
            chatService.saveAssistantMessage(assistantMsgId, sessionId, tip, null, "NEED_CONFIRM",
                    (int) (System.currentTimeMillis() - startMs));
            ChatResponseVO resp = new ChatResponseVO();
            resp.setReplyMessage(tip);
            resp.setMessageId(assistantMsgId);
            resp.setSessionId(sessionId);
            resp.setFinishReason("NEED_CONFIRM");
            resp.setConfirmThreadId(threadId);
            resp.setLatencyMs(System.currentTimeMillis() - startMs);
            return resp;
        }

        // 抢占 busy：并发双击「确认」时只有一个能续跑
        if (!confirmBusySessions.add(sessionId)) {
            return tryBusyConfirmResponse(sessionId, userId, message);
        }
        pendingConfirmBySession.remove(sessionId);
        long startMs = System.currentTimeMillis();
        usageMetricsService.beginTurn(userId, sessionId);
        try {
            chatService.saveUserMessage(
                    "msg_u_" + UUID.randomUUID().toString().substring(0, 8),
                    sessionId, userId, message, null, MessageType.TEXT);

            log.info("[桌面WRITE] sessionId={} {} threadId={} parallel={}",
                    sessionId, confirmed ? "确认" : "取消", threadId, batchHead != null);
            AgentResult agentResult = agentGraphRunner.confirmResume(threadId, confirmed);

            // 并行批次：逐个确认，全部完成后汇总
            if (parallelBatchService.getBySession(sessionId) != null) {
                ResumeOutcome outcome = parallelBatchService.onResumeResult(sessionId, agentResult);
                return buildParallelResumeResponse(sessionId, startMs, outcome, agentResult);
            }

            String reply;
            String finishReason;
            if (agentResult.needsConfirm()) {
                pendingConfirmBySession.put(sessionId, agentResult.getConfirmThreadId());
                reply = agentResult.getSummary();
                finishReason = "NEED_CONFIRM";
            } else if (agentResult.isOk()) {
                reply = agentResult.getSummary();
                finishReason = "STOP";
            } else {
                reply = agentResult.getError() != null ? agentResult.getError() : "操作失败，请重试";
                finishReason = "ERROR";
            }

            int latencyMs = (int) (System.currentTimeMillis() - startMs);
            String assistantMsgId = "msg_a_" + UUID.randomUUID().toString().substring(0, 8);
            List<Map<String, Object>> attachments = buildClientAttachments(
                    agentResult.getAttachments(), reply);
            String displayReply = stripFileMarkers(reply);
            chatService.saveAssistantMessage(assistantMsgId, sessionId,
                    displayReply != null ? displayReply : "", null, finishReason, latencyMs);

            ChatResponseVO resp = new ChatResponseVO();
            resp.setReplyMessage(displayReply);
            resp.setMessageId(assistantMsgId);
            resp.setSessionId(sessionId);
            resp.setFinishReason(finishReason);
            resp.setLatencyMs((long) latencyMs);
            resp.setAttachments(attachments.isEmpty() ? null : attachments);
            if (agentResult.needsConfirm()) {
                resp.setConfirmThreadId(agentResult.getConfirmThreadId());
            }
            usageMetricsService.endTurn(latencyMs);
            return resp;
        } catch (RuntimeException e) {
            usageMetricsService.endTurn((int) (System.currentTimeMillis() - startMs));
            throw e;
        } finally {
            confirmBusySessions.remove(sessionId);
        }
    }

    private ChatResponseVO buildParallelResumeResponse(String sessionId, long startMs,
                                                       ResumeOutcome outcome, AgentResult lastResult) {
        int latencyMs = (int) (System.currentTimeMillis() - startMs);
        String assistantMsgId = "msg_a_" + UUID.randomUUID().toString().substring(0, 8);

        if (outcome.kind == ResumeOutcome.Kind.NEXT_CONFIRM) {
            ConfirmItem next = outcome.nextConfirm;
            pendingConfirmBySession.put(sessionId, next.threadId);
            String reply = ParallelAgentBatchService.formatConfirmMessage(next, outcome.remainingConfirms);
            String displayReply = stripFileMarkers(reply);
            chatService.saveAssistantMessage(assistantMsgId, sessionId,
                    displayReply, null, "NEED_CONFIRM", latencyMs);
            ChatResponseVO resp = new ChatResponseVO();
            resp.setReplyMessage(displayReply);
            resp.setMessageId(assistantMsgId);
            resp.setSessionId(sessionId);
            resp.setFinishReason("NEED_CONFIRM");
            resp.setLatencyMs((long) latencyMs);
            resp.setConfirmThreadId(next.threadId);
            usageMetricsService.endTurn(latencyMs);
            log.info("[桌面WRITE] 并行批次下一确认 sessionId={} threadId={} remaining={}",
                    sessionId, next.threadId, outcome.remainingConfirms);
            return resp;
        }

        if (outcome.kind == ResumeOutcome.Kind.FINISHED) {
            String reply = parallelBatchService.aggregate(outcome.batch);
            if (reply == null || reply.isBlank()) {
                reply = lastResult != null && lastResult.isOk() ? lastResult.getSummary()
                        : (lastResult != null && lastResult.getError() != null
                        ? lastResult.getError() : "已完成");
            }
            List<Map<String, Object>> attachments = buildClientAttachments(
                    parallelBatchService.allAttachments(outcome.batch), reply);
            parallelBatchService.clear(sessionId);
            pendingConfirmBySession.remove(sessionId);
            String displayReply = stripFileMarkers(reply);
            chatService.saveAssistantMessage(assistantMsgId, sessionId,
                    displayReply != null ? displayReply : "", null, "STOP", latencyMs);
            ChatResponseVO resp = new ChatResponseVO();
            resp.setReplyMessage(displayReply);
            resp.setMessageId(assistantMsgId);
            resp.setSessionId(sessionId);
            resp.setFinishReason("STOP");
            resp.setLatencyMs((long) latencyMs);
            resp.setAttachments(attachments.isEmpty() ? null : attachments);
            usageMetricsService.endTurn(latencyMs);
            log.info("[桌面WRITE] 并行批次汇总完成 sessionId={} replyLen={}",
                    sessionId, displayReply != null ? displayReply.length() : 0);
            return resp;
        }

        // NONE：降级按单次结果
        String reply = lastResult != null && lastResult.isOk() ? lastResult.getSummary()
                : (lastResult != null && lastResult.getError() != null ? lastResult.getError() : "操作失败");
        String displayReply = stripFileMarkers(reply);
        chatService.saveAssistantMessage(assistantMsgId, sessionId,
                displayReply != null ? displayReply : "", null, "STOP", latencyMs);
        ChatResponseVO resp = new ChatResponseVO();
        resp.setReplyMessage(displayReply);
        resp.setMessageId(assistantMsgId);
        resp.setSessionId(sessionId);
        resp.setFinishReason("STOP");
        resp.setLatencyMs((long) latencyMs);
        usageMetricsService.endTurn(latencyMs);
        return resp;
    }

    /** 并行确认队列优先，否则从本轮 messageHistory 解析单次 WRITE */
    private ConfirmInfo resolveConfirm(String sessionId, ChatGraphState state) {
        ConfirmItem head = parallelBatchService.peekConfirm(sessionId);
        if (head != null) {
            var b = parallelBatchService.getBySession(sessionId);
            int q = b != null ? b.confirmQueue.size() : 1;
            String msg = ParallelAgentBatchService.formatConfirmMessage(head, q);
            // 若图已写入 reply，优先用图里的（已含队列提示）
            if (Boolean.TRUE.equals(state.data().get(ChatGraphState.KEY_AWAIT_PARALLEL_CONFIRM))
                    && state.getReply() != null && !state.getReply().isBlank()) {
                msg = state.getReply();
            }
            return new ConfirmInfo(head.threadId, head.toolName, msg, true);
        }
        ConfirmInfo single = extractConfirm(state);
        if (single == null) return null;
        // 历史 tool 结果里可能残留 needsConfirm=true；仅当 Agent 侧仍挂起时才弹确认
        if (!agentGraphRunner.hasPendingConfirm(single.threadId())) {
            log.info("[桌面WRITE] 忽略过期 needsConfirm threadId={} tool={}（已确认/取消或不存在）",
                    single.threadId(), single.toolName());
            return null;
        }
        return new ConfirmInfo(single.threadId(), single.toolName(), single.confirmMsg(), false);
    }

    /** 剥掉 [FILE:…]，避免桌面气泡露出绝对路径 */
    private static String stripFileMarkers(String reply) {
        if (reply == null || reply.isBlank()) return reply == null ? "" : reply;
        return FILE_MARKER.matcher(reply).replaceAll("")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 合并 state/AgentResult 附件与回复中的 [FILE:]，输出仅含 type/url/fileName 的客户端安全列表。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildClientAttachments(Object rawAtts, String reply) {
        List<Map<String, Object>> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        if (rawAtts instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, Object> client = toClientAttachment((Map<String, Object>) m);
                if (client == null) continue;
                String key = "route".equals(client.get("type"))
                        ? "route:" + client.get("origin") + "->" + client.get("destination")
                        : String.valueOf(client.get("url"));
                if (seen.add(key)) out.add(client);
            }
        }

        if (reply != null) {
            Matcher matcher = FILE_MARKER.matcher(reply);
            while (matcher.find()) {
                String path = matcher.group(1).trim().replace("\\", "/");
                Map<String, Object> fromPath = attachmentFromDiskPath(path, "file");
                if (fromPath == null) continue;
                String key = String.valueOf(fromPath.get("url"));
                if (seen.add(key)) out.add(fromPath);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toClientAttachment(Map<String, Object> src) {
        if (src == null) return null;
        String type = src.get("type") != null ? String.valueOf(src.get("type")) : null;

        // 路线地图附件：无 url，整包下发供桌面硬编码渲染
        if ("route".equals(type)) {
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("type", "route");
            for (String k : List.of(
                    "origin", "destination", "originLngLat", "destLngLat",
                    "path", "distanceMeters", "durationSeconds", "mode")) {
                if (src.get(k) != null) client.put(k, src.get(k));
            }
            if (client.get("path") == null
                    && (client.get("origin") == null || client.get("destination") == null)) {
                return null;
            }
            return client;
        }

        String url = src.get("url") != null ? String.valueOf(src.get("url")) : null;
        String fileName = src.get("fileName") != null ? String.valueOf(src.get("fileName")) : null;
        String filePath = src.get("filePath") != null ? String.valueOf(src.get("filePath")) : null;

        if ((url == null || url.isBlank()) && filePath != null) {
            Map<String, Object> rebuilt = attachmentFromDiskPath(
                    filePath.replace("\\", "/"),
                    type != null ? type : "file");
            if (rebuilt != null) return rebuilt;
        }
        if (url == null || url.isBlank()) return null;

        Map<String, Object> client = new LinkedHashMap<>();
        client.put("type", type != null ? type : "file");
        client.put("url", url);
        if (fileName != null && !fileName.isBlank()) {
            client.put("fileName", fileName);
        }
        return client;
    }

    private Map<String, Object> attachmentFromDiskPath(String path, String type) {
        if (path == null || path.isBlank()) return null;
        String norm = path.replace("\\", "/");
        Path p = Paths.get(norm);
        String fileName = p.getFileName() != null ? p.getFileName().toString() : null;
        if (fileName == null || fileName.isBlank()) return null;

        Map<String, Object> att = new LinkedHashMap<>();
        if ("image".equals(type) || SAFE_SHOT_NAME.matcher(fileName).matches()) {
            att.put("type", "image");
            att.put("fileName", fileName);
            att.put("url", ApiPrefix.CHAT + "/screenshots/" + fileName);
            return att;
        }

        // 1) generateDocument 产物（./AItext/日期/文件）— 优先于沙箱，避免相对路径误挂到 upload/
        Path docgenRoot = Paths.get(docgenDir).toAbsolutePath().normalize();
        Path abs = p.isAbsolute()
                ? p.toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath().resolve(p).normalize();
        if (!p.isAbsolute()) {
            String stripped = norm.startsWith("./") ? norm.substring(2) : norm;
            if (stripped.startsWith("AItext/")) {
                abs = docgenRoot.resolve(stripped.substring("AItext/".length())).normalize();
            }
        }
        if (abs.startsWith(docgenRoot)) {
            Path rel = docgenRoot.relativize(abs);
            if (rel.getNameCount() >= 2) {
                String date = rel.getName(0).toString();
                if (SAFE_DATE_DIR.matcher(date).matches()) {
                    String encodedName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    att.put("type", "file");
                    att.put("fileName", fileName);
                    att.put("url", ApiPrefix.CHAT + "/generated/" + date + "/" + encodedName);
                    return att;
                }
            }
        }

        // 2) filesystem MCP 沙箱文件（./upload/...）
        Path sandboxRoot = Paths.get(sandboxDir).toAbsolutePath().normalize();
        Path sandboxAbs = p.isAbsolute() ? p.toAbsolutePath().normalize() : sandboxRoot.resolve(p).normalize();
        String strippedForSandbox = norm.startsWith("./") ? norm.substring(2) : norm;
        if (!strippedForSandbox.startsWith("AItext/")
                && sandboxAbs.startsWith(sandboxRoot)
                && sandboxAbs.getNameCount() > sandboxRoot.getNameCount()) {
            Path rel = sandboxRoot.relativize(sandboxAbs);
            StringBuilder url = new StringBuilder(ApiPrefix.CHAT).append("/sandbox/");
            for (int i = 0; i < rel.getNameCount(); i++) {
                if (i > 0) url.append('/');
                url.append(java.net.URLEncoder.encode(rel.getName(i).toString(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"));
            }
            att.put("type", "file");
            att.put("fileName", fileName);
            att.put("url", url.toString());
            return att;
        }

        // 3) 路径中带日期目录的兜底
        String date = null;
        if (p.getNameCount() >= 2) {
            date = p.getName(p.getNameCount() - 2).toString();
        }
        if (date != null && SAFE_DATE_DIR.matcher(date).matches()) {
            String encodedName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            att.put("type", "file");
            att.put("fileName", fileName);
            att.put("url", ApiPrefix.CHAT + "/generated/" + date + "/" + encodedName);
            return att;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ConfirmInfo extractConfirm(ChatGraphState state) {
        Object histObj = state.data().get("messageHistory");
        if (!(histObj instanceof List<?> hist) || hist.isEmpty()) {
            histObj = state.data().get(ChatGraphState.KEY_MESSAGES);
        }
        if (!(histObj instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        // 只扫「本轮最后一条 user 之后」的 tool，避免并行批次已完成后历史 needsConfirm 再次弹窗
        int lastUser = -1;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> msg)) continue;
            if ("user".equals(msg.get("role"))) {
                lastUser = i;
            }
        }
        int from = lastUser >= 0 ? lastUser + 1 : 0;
        for (int i = list.size() - 1; i >= from; i--) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> msg)) continue;
            if (!"tool".equals(msg.get("role"))) continue;
            Object content = msg.get("content");
            if (!(content instanceof String text) || !text.contains("\"needsConfirm\"")) continue;
            try {
                JsonNode node = objectMapper.readTree(text);
                if (!node.path("needsConfirm").asBoolean(false)) continue;
                String threadId = node.path("threadId").asText(null);
                String confirmMsg = node.path("confirmMsg").asText(null);
                String toolName = node.path("toolName").asText(null);
                if (threadId == null || threadId.isBlank()) continue;
                if (confirmMsg == null || confirmMsg.isBlank()) {
                    confirmMsg = interruptHandler.buildConfirmMessage(
                            toolName != null ? toolName : "操作", "{}");
                }
                return new ConfirmInfo(threadId, toolName, confirmMsg, false);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private record ConfirmInfo(String threadId, String toolName, String confirmMsg, boolean fromParallelBatch) {}
}
