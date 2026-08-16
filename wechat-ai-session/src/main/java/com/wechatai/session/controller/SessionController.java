package com.wechatai.session.controller;

import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import com.wechatai.session.model.request.UpdateTitleReq;
import com.wechatai.session.model.vo.SessionVO;
import com.wechatai.session.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理 REST 接口（文档 3.2～3.5）。
 * <p>
 * 第一期主要为管理后台预留（查详情/列表、改标题、软删）；
 * 微信场景下会话由 {@link SessionService#getOrCreateActiveSession} 内部自动管理，
 * 用户不感知会话列表。创建接口不对外暴露。
 * <p>
 * 统一外层响应：{@code Result} → { code, message, data, traceId, timestamp }
 */
@RestController
@RequestMapping(ApiPrefix.SESSION)
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * 3.2 获取会话详情
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/session/{sessionId}
     *
     * 请求参数:
     *   Path: sessionId (string) — 会话业务 UUID
     *   Body: 无
     *
     * 响应 data (SessionVO):
     * {
     *   "sessionId": "6b3e8f20-...",
     *   "userId": "oWx_xxxxx",
     *   "title": "关于合同条款的咨询",
     *   "status": "ACTIVE",          // ACTIVE / EXPIRED / DELETED
     *   "messageCount": 23,
     *   "lastMessageAt": "2026-07-15 14:30:00",
     *   "createdAt": "2026-07-15 10:30:00",
     *   "expireAt": "2026-07-16 10:30:00"
     * }
     * </pre>
     */
    @GetMapping("/{sessionId}")
    public Result<SessionVO> get(@PathVariable String sessionId) {
        return Result.success(sessionService.getSession(sessionId));
    }

    /**
     * 3.3 获取用户会话列表
     * <pre>
     * 请求类型: GET
     * URL:      /api/v1/session/list?userId={userId}
     *
     * 请求参数:
     *   Query: userId (string, 必填) — 微信用户 openid
     *   Body:  无
     *
     * 响应 data (List&lt;SessionVO&gt;，第一期不分页，直接数组):
     * [
     *   {
     *     "sessionId": "6b3e8f20-...",
     *     "title": "关于合同条款的咨询",
     *     "status": "ACTIVE",
     *     "messageCount": 23,
     *     "lastMessageAt": "2026-07-15 14:30:00",
     *     "createdAt": "2026-07-15 10:30:00"
     *   }
     * ]
     * </pre>
     */
    @GetMapping("/list")
    public Result<List<SessionVO>> list(@RequestParam String userId) {
        return Result.success(sessionService.listByUserId(userId));
    }

    /**
     * 3.4 更新会话标题
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/session/{sessionId}/title
     * Content-Type: application/json
     *
     * 请求参数:
     *   Path: sessionId (string)
     *   Body (UpdateTitleReq):
     *   {
     *     "title": "新的会话标题"
     *   }
     *
     * 响应 data: null
     * </pre>
     */
    @PostMapping("/{sessionId}/title")
    public Result<Void> updateTitle(@PathVariable String sessionId,
                                    @Valid @RequestBody UpdateTitleReq req) {
        sessionService.updateTitle(sessionId, req.getTitle());
        return Result.success();
    }

    /**
     * 3.5 删除会话（逻辑删除，status=DELETED，30 天后物理清理）
     * <pre>
     * 请求类型: POST
     * URL:      /api/v1/session/{sessionId}/delete
     *
     * 请求参数:
     *   Path: sessionId (string)
     *   Body: 无
     *
     * 响应 data: null
     * </pre>
     */
    @PostMapping("/{sessionId}/delete")
    public Result<Void> delete(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success();
    }
}
