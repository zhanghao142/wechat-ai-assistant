package com.wechatai.document.controller;

import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import com.wechatai.document.model.request.ParseReq;
import com.wechatai.document.model.vo.DocumentContentVO;
import com.wechatai.document.model.vo.DocumentVO;
import com.wechatai.document.model.vo.ParseTaskVO;
import com.wechatai.document.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理 REST 接口（文档第 4 章）。
 * <p>
 * 覆盖上传、异步解析入队、状态查询、列表、删除与内容读取。
 * 限制：pdf/doc/docx/txt，单文件 ≤ 50MB。content 接口可供调试，
 * AI 侧优先同 JVM 调 DocumentService.getContent。
 * <p>
 * 统一外层响应：{@code Result} → { code, message, data, traceId, timestamp }
 */
@RestController
@RequestMapping(ApiPrefix.DOCUMENT)
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 4.1 上传文档
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/document/upload
     * Content-Type: multipart/form-data
     *
     * 请求参数 (form-data，非 JSON):
     *   file      (File,   必填) — 文档二进制，支持 pdf/doc/docx/txt，≤ 50MB
     *   sessionId (string, 必填) — 所属会话 ID
     *   userId    (string, 必填) — 用户 openid
     *
     * 响应 data (DocumentVO):
     * {
     *   "fileId": "f_a1b2c3d4",
     *   "fileName": "合同模板.pdf",
     *   "fileSize": 2048576,
     *   "fileType": "pdf",
     *   "status": "UPLOADED",       // UPLOADED / PARSING / PARSED / FAILED
     *   "uploadedAt": "2026-07-15 10:30:00"
     * }
     * </pre>
     */
    @PostMapping("/upload")
    public Result<DocumentVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam String sessionId,
                                     @RequestParam String userId) {
        return Result.success(documentService.upload(file, sessionId, userId));
    }

    /**
     * 4.2 发起文档解析（入 Redis 队列，后台 Worker 异步执行）
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/document/parse
     * Content-Type: application/json
     *
     * 请求体 (ParseReq):
     * {
     *   "fileId": "f_a1b2c3d4"
     * }
     *
     * 响应 data (ParseTaskVO):
     * {
     *   "taskId": "task_x1y2z3",
     *   "fileId": "f_a1b2c3d4",
     *   "status": "QUEUED",         // QUEUED / PARSING / DONE / FAILED
     *   "estimatedSeconds": 15
     * }
     * </pre>
     */
    @PostMapping("/parse")
    public Result<ParseTaskVO> parse(@Valid @RequestBody ParseReq req) {
        return Result.success(documentService.parse(req.getFileId()));
    }

    /**
     * 4.3 查询文档解析状态
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/document/{fileId}/status
     *
     * 请求参数:
     *   Path: fileId (string)
     *   Body: 无
     *
     * 响应 data (DocumentVO):
     * {
     *   "fileId": "f_a1b2c3d4",
     *   "fileName": "合同模板.pdf",
     *   "fileType": "pdf",
     *   "status": "PARSED",
     *   "parsedAt": "2026-07-15 10:30:30"
     * }
     * </pre>
     */
    @GetMapping("/{fileId}/status")
    public Result<DocumentVO> status(@PathVariable String fileId) {
        return Result.success(documentService.getStatus(fileId));
    }

    /**
     * 4.4 获取文档列表（按会话）
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/document/list?sessionId={sessionId}&amp;status={status}
     *
     * 请求参数:
     *   Query: sessionId (string, 必填) — 会话 ID
     *   Query: status    (string, 可选) — 按状态筛选，如 PARSED
     *   Body:  无
     *
     * 响应 data (List&lt;DocumentVO&gt;，直接数组):
     * [
     *   {
     *     "fileId": "f_a1b2c3d4",
     *     "fileName": "合同模板.pdf",
     *     "fileSize": 2048576,
     *     "fileType": "pdf",
     *     "status": "PARSED",
     *     "uploadedAt": "2026-07-15 10:30:00",
     *     "parsedAt": "2026-07-15 10:30:30"
     *   }
     * ]
     * </pre>
     */
    @GetMapping("/list")
    public Result<List<DocumentVO>> list(@RequestParam String sessionId,
                                         @RequestParam(required = false) String status) {
        return Result.success(documentService.listBySession(sessionId, status));
    }

    /**
     * 4.5 删除文档（不可恢复：同时删存储文件 + MySQL 记录含解析文本）
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/document/{fileId}/delete
     *
     * 请求参数:
     *   Path: fileId (string)
     *   Body: 无
     *
     * 响应 data: null
     * </pre>
     */
    @PostMapping("/{fileId}/delete")
    public Result<Void> delete(@PathVariable String fileId) {
        documentService.delete(fileId);
        return Result.success();
    }

    /**
     * 4.6 获取文档内容（已解析纯文本；单次最多约 10 万字符）
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/document/{fileId}/content
     *
     * 请求参数:
     *   Path: fileId (string)
     *   Body: 无
     *
     * 响应 data (DocumentContentVO):
     * {
     *   "fileId": "f_a1b2c3d4",
     *   "fileName": "合同模板.pdf",
     *   "content": "（全文文本内容）...",
     *   "contentType": "PLAIN_TEXT"   // PLAIN_TEXT / MARKDOWN
     * }
     * </pre>
     */
    @GetMapping("/{fileId}/content")
    public Result<DocumentContentVO> content(@PathVariable String fileId) {
        return Result.success(documentService.getContent(fileId));
    }
}
