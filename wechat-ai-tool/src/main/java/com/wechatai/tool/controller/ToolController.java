package com.wechatai.tool.controller;

import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.enums.ToolCategory;
import com.wechatai.common.model.Result;
import com.wechatai.tool.model.request.ToggleToolReq;
import com.wechatai.tool.model.vo.ToolHistoryVO;
import com.wechatai.tool.model.vo.ToolListVO;
import com.wechatai.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 工具运维 REST 接口（文档 6.1 / 6.6）。
 * <p>
 * 工具真正执行走 LangChain4j 同 JVM 调用，不提供 HTTP execute。
 * 本 Controller 仅用于列表、调用历史与启停，供管理后台使用。
 * <p>
 * 统一外层响应：{@code Result} → { code, message, data, traceId, timestamp }
 */
@RestController
@RequestMapping(ApiPrefix.TOOLS)
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(@Lazy ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 6.1 获取可用工具列表
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/tools/list?sessionId={sessionId}&amp;category={category}
     *
     * 请求参数:
     *   Query: sessionId (string, 必填) — 会话 ID
     *   Query: category  (string, 可选) — READ / WRITE / SYSTEM，不传返回全部
     *   Body:  无
     *
     * 响应 data (ToolListVO):
     * {
     *   "tools": [
     *     {
     *       "name": "search_document",
     *       "description": "在当前会话的已解析文档中搜索...",
     *       "category": "READ",               // READ / WRITE / SYSTEM
     *       "enabled": true,
     *       "parameters": {                   // JSON Schema
     *         "type": "object",
     *         "properties": { ... },
     *         "required": ["keywords"]
     *       }
     *     }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/list")
    public Result<ToolListVO> list(@RequestParam String sessionId,
                                   @RequestParam(required = false) ToolCategory category) {
        ToolListVO vo = new ToolListVO();
        vo.setTools(toolRegistry.listTools(sessionId, category));
        return Result.success(vo);
    }

    /**
     * 6.6.1 查询工具调用历史
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/tools/history?sessionId={sessionId}
     *
     * 请求参数:
     *   Query: sessionId (string, 必填)
     *   Body:  无
     *
     * 响应 data (List&lt;ToolHistoryVO&gt;，直接数组):
     * [
     *   {
     *     "toolCallId": "call_abc123",
     *     "toolName": "search_document",
     *     "arguments": "{\"keywords\":\"违约条款\"}",
     *     "success": true,
     *     "resultSummary": "找到 3 条匹配结果",
     *     "latencyMs": 850,
     *     "calledAt": "2026-07-15 10:30:00"
     *   }
     * ]
     * </pre>
     */
    @GetMapping("/history")
    public Result<List<ToolHistoryVO>> history(@RequestParam String sessionId) {
        return Result.success(toolRegistry.getHistory(sessionId));
    }

    /**
     * 6.6.2 禁用/启用工具
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/tools/{toolName}/toggle
     * Content-Type: application/json
     *
     * 请求参数:
     *   Path: toolName (string) — 工具名，如 search_document
     *   Body (ToggleToolReq):
     *   {
     *     "enabled": false
     *   }
     *
     * 响应 data: null
     * </pre>
     */
    @PostMapping("/{toolName}/toggle")
    public Result<Void> toggle(@PathVariable String toolName, @RequestBody ToggleToolReq req) {
        toolRegistry.toggle(toolName, req.isEnabled());
        return Result.success();
    }
}
