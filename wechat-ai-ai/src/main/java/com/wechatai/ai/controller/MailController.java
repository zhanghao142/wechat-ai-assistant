package com.wechatai.ai.controller;

import com.wechatai.ai.entity.AppUserEntity;
import com.wechatai.ai.service.AppUserService;
import com.wechatai.ai.service.EmailCodeService;
import com.wechatai.common.constant.ApiPrefix;
import com.wechatai.common.model.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 邮件相关 HTTP 接口（桌面端登录验证码等）。
 */
@RestController
@RequestMapping(ApiPrefix.MAIL)
@RequiredArgsConstructor
public class MailController {

    private final EmailCodeService emailCodeService;
    private final AppUserService appUserService;

    /**
     * 发送登录验证码邮件。
     * <pre>
     * POST /api/v1/mail/send-code
     * Body: { "email": "xxx@qq.com" }
     * </pre>
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody SendCodeRequest req) {
        if (req == null || req.getEmail() == null || req.getEmail().isBlank()) {
            return Result.error(400, "邮箱不能为空");
        }
        String err = emailCodeService.sendCode(req.getEmail());
        if (err != null) {
            return Result.error(400, err);
        }
        return Result.success();
    }

    /**
     * 校验登录验证码；通过后查找或创建 app_user，返回 userId。
     * <pre>
     * POST /api/v1/mail/verify-code
     * Body: { "email": "xxx@qq.com", "code": "123456" }
     * Resp: { userId, email }
     * </pre>
     */
    @PostMapping("/verify-code")
    public Result<LoginResponse> verifyCode(@RequestBody VerifyCodeRequest req) {
        if (req == null || req.getEmail() == null || req.getEmail().isBlank()) {
            return Result.error(400, "邮箱不能为空");
        }
        String err = emailCodeService.verifyCode(req.getEmail(), req.getCode());
        if (err != null) {
            return Result.error(400, err);
        }

        String email = req.getEmail().trim().toLowerCase();
        AppUserEntity user = appUserService.findOrCreateByEmail(email);

        LoginResponse resp = new LoginResponse();
        resp.setUserId(user.getUserId());
        resp.setEmail(user.getEmail());
        return Result.success(resp);
    }

    @Data
    public static class SendCodeRequest {
        private String email;
    }

    @Data
    public static class VerifyCodeRequest {
        private String email;
        private String code;
    }

    @Data
    public static class LoginResponse {
        private String userId;
        private String email;
    }
}
