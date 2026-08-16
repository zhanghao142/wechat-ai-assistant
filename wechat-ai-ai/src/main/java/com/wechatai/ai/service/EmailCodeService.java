package com.wechatai.ai.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮箱验证码：生成、发送、校验（内存存储，适合课设/演示）。
 */
@Slf4j
@Service
public class EmailCodeService {

    private static final long CODE_TTL_MS = 5 * 60 * 1000L;
    private static final long SEND_INTERVAL_MS = 60 * 1000L;

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final SecureRandom random = new SecureRandom();

    /** email → 验证码记录 */
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    public EmailCodeService(JavaMailSender mailSender,
                            @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * 生成 6 位验证码并发送到邮箱。
     *
     * @return null 成功；非 null 为错误信息
     */
    public String sendCode(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return "邮箱格式不正确";
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            return "邮件服务未配置（spring.mail.username）";
        }

        CodeEntry existing = codeStore.get(normalized);
        long now = System.currentTimeMillis();
        if (existing != null && now - existing.sentAt < SEND_INTERVAL_MS) {
            long wait = (SEND_INTERVAL_MS - (now - existing.sentAt) + 999) / 1000;
            return "发送过于频繁，请 " + wait + " 秒后再试";
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(normalized);
            helper.setSubject("【小微】登录验证码");
            helper.setText(
                    "您的登录验证码是：" + code + "\n\n"
                            + "5 分钟内有效，请勿泄露给他人。\n"
                            + "如非本人操作，请忽略本邮件。",
                    false
            );
            mailSender.send(message);
        } catch (Exception e) {
            log.error("[邮箱验证码] 发送失败 email={}", normalized, e);
            return "邮件发送失败：" + e.getMessage();
        }

        codeStore.put(normalized, new CodeEntry(code, now));
        log.info("[邮箱验证码] 已发送 email={}", normalized);
        return null;
    }

    /**
     * 校验验证码。
     *
     * @return null 成功；非 null 为错误信息
     */
    public String verifyCode(String email, String code) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return "邮箱格式不正确";
        }
        String c = code == null ? "" : code.trim();
        if (!c.matches("\\d{6}")) {
            return "验证码格式不正确";
        }

        CodeEntry entry = codeStore.get(normalized);
        if (entry == null) {
            return "请先获取验证码";
        }
        if (System.currentTimeMillis() - entry.sentAt > CODE_TTL_MS) {
            codeStore.remove(normalized);
            return "验证码已过期，请重新获取";
        }
        if (!entry.code.equals(c)) {
            return "验证码错误";
        }
        codeStore.remove(normalized);
        return null;
    }

    private static String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        if (!e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return null;
        }
        return e;
    }

    private static final class CodeEntry {
        final String code;
        final long sentAt;

        CodeEntry(String code, long sentAt) {
            this.code = code;
            this.sentAt = sentAt;
        }
    }
}
