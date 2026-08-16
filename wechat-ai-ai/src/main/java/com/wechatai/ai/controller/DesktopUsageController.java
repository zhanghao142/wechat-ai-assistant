package com.wechatai.ai.controller;

import com.wechatai.ai.metrics.UsageMetricsService;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 桌面端用量统计 API。
 */
@RestController
@RequestMapping(ApiPrefix.DESKTOP)
@RequiredArgsConstructor
public class DesktopUsageController {

    private final UsageMetricsService usageMetricsService;

    /**
     * GET /api/v1/desktop/usage?userId=xxx&limit=30
     */
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage(@RequestParam String userId,
                                             @RequestParam(defaultValue = "30") int limit) {
        return Result.success(usageMetricsService.snapshot(userId, limit));
    }
}
