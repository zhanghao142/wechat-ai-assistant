package com.wechatai.wechat.controller;

import com.wechatai.common.enums.ClientStatus;
import com.wechatai.common.model.Result;
import com.wechatai.wechat.multi.WechatClientRegistry;
import com.wechatai.wechat.exception.ClientLimitReachedException;
import com.wechatai.wechat.exception.ClientNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多账号 Admin REST API — 管理微信 Bot 客户端生命周期。
 * <p>
 * 仅在 {@code wechat.multi-account.enabled=true} 时激活。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/wechat-clients")
@ConditionalOnProperty(name = "wechat.multi-account.enabled", havingValue = "true")
public class WechatClientAdminController {

    private final WechatClientRegistry registry;

    public WechatClientAdminController(WechatClientRegistry registry) {
        this.registry = registry;
    }

    /** 创建新 client（返回二维码 URL） */
    @PostMapping
    public Result<Map<String, Object>> createClient(@RequestParam(required = false) String clientId) {
        try {
            String qrcodeUrl = registry.register(clientId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("qrcodeUrl", qrcodeUrl);
            data.put("needScan", qrcodeUrl != null);
            return Result.success(data);
        } catch (ClientLimitReachedException e) {
            return Result.error(429, e.getMessage());
        }
    }

    /** 列出所有 client 状态 */
    @GetMapping
    public Result<List<Map<String, Object>>> listClients() {
        List<String> ids = registry.listClientIds();
        List<Map<String, Object>> list = ids.stream().map(id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", id);
            m.put("status", registry.getStatus(id));
            return m;
        }).toList();
        return Result.success(list);
    }

    /** 单个 client 详情 */
    @GetMapping("/{clientId}")
    public Result<Map<String, Object>> getClient(@PathVariable String clientId) {
        try {
            ClientStatus status = registry.getStatus(clientId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientId", clientId);
            data.put("status", status);
            data.put("qrcodeUrl", registry.getQrcodeUrl(clientId));
            return Result.success(data);
        } catch (ClientNotFoundException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /** 获取二维码 URL */
    @GetMapping("/{clientId}/qrcode")
    public Result<Map<String, String>> getQrcode(@PathVariable String clientId) {
        try {
            String url = registry.getQrcodeUrl(clientId);
            return Result.success(Map.of("clientId", clientId, "qrcodeUrl", url != null ? url : ""));
        } catch (ClientNotFoundException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /** 强制重新登录 */
    @PostMapping("/{clientId}/relogin")
    public Result<Map<String, Object>> relogin(@PathVariable String clientId) {
        try {
            String qrcodeUrl = registry.relogin(clientId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("clientId", clientId);
            data.put("qrcodeUrl", qrcodeUrl);
            return Result.success(data);
        } catch (ClientNotFoundException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /** 删除 client */
    @DeleteMapping("/{clientId}")
    public Result<Void> deleteClient(@PathVariable String clientId) {
        try {
            registry.remove(clientId);
            return Result.success();
        } catch (ClientNotFoundException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
