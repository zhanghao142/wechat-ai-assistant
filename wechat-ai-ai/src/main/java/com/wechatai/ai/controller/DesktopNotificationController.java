package com.wechatai.ai.controller;

import com.wechatai.ai.service.DesktopNewsSuggestionService;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import com.wechatai.session.entity.DesktopNotificationEntity;
import com.wechatai.session.service.DesktopNotificationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 桌面端通知 API — 与 iLink 无关。
 */
@RestController
@RequestMapping(ApiPrefix.DESKTOP)
@RequiredArgsConstructor
public class DesktopNotificationController {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DesktopNotificationService notificationService;
    private final DesktopNewsSuggestionService newsSuggestionService;

    /**
     * GET /api/v1/desktop/notifications?userId=xxx
     */
    @GetMapping("/notifications")
    public Result<List<NotificationVO>> listUnread(@RequestParam String userId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        List<NotificationVO> list = notificationService.listUnread(userId, Math.min(limit, 50))
                .stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(list);
    }

    /**
     * POST /api/v1/desktop/notifications/{notificationId}/read?userId=xxx
     */
    @PostMapping("/notifications/{notificationId}/read")
    public Result<Void> markRead(@PathVariable String notificationId,
                                 @RequestParam String userId) {
        notificationService.markRead(notificationId, userId);
        return Result.success();
    }

    /**
     * POST /api/v1/desktop/notifications/read-all?userId=xxx
     */
    @PostMapping("/notifications/read-all")
    public Result<Void> markAllRead(@RequestParam String userId) {
        notificationService.markAllRead(userId);
        return Result.success();
    }

    /**
     * GET /api/v1/desktop/suggestions/news?limit=7
     * 欢迎页资讯胶囊：返回标题 + 摘要正文（独立抓取，不经过工具类）。
     */
    @GetMapping("/suggestions/news")
    public Result<List<DesktopNewsSuggestionService.NewsItem>> newsSuggestions(
            @RequestParam(defaultValue = "7") int limit) {
        return Result.success(newsSuggestionService.listNews(limit));
    }

    private NotificationVO toVO(DesktopNotificationEntity e) {
        NotificationVO vo = new NotificationVO();
        vo.setNotificationId(e.getNotificationId());
        vo.setSessionId(e.getSessionId());
        vo.setRemindId(e.getRemindId());
        vo.setContent(e.getContent());
        vo.setCreatedAt(e.getCreatedAt() != null ? e.getCreatedAt().format(DTF) : null);
        return vo;
    }

    @Data
    public static class NotificationVO {
        private String notificationId;
        private String sessionId;
        private String remindId;
        private String content;
        private String createdAt;
    }
}
