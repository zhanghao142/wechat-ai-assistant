package com.wechatai.tool.impl;

import com.wechatai.tool.annotation.WriteTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件发送工具（WRITE）— 通过 SMTP 发送电子邮件。
 * <p>
 * 使用 Spring JavaMailSender 发送，支持纯文本邮件。
 * 发送前需要用户在对话中确认收件人和内容（由 EmailSkill 保证）。
 */
@Component
@WriteTool("发送电子邮件")
public class SendEmailTool {

    private static final Logger log = LoggerFactory.getLogger(SendEmailTool.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SendEmailTool(JavaMailSender mailSender,
                         @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Tool("发送电子邮件。当用户说「帮我发邮件」「发邮件给xxx」「邮件发送」时调用。"
            + "发送成功后告知用户发送结果。")
    public String sendEmail(
            @P("收件人邮箱地址") String to,
            @P("邮件主题") String subject,
            @P("邮件正文内容") String body) {

        if (to == null || to.trim().isEmpty()) {
            return "收件人地址不能为空";
        }
        if (!to.contains("@")) {
            return "邮箱地址格式不正确，请提供有效的邮箱地址";
        }
        if (subject == null || subject.trim().isEmpty()) {
            subject = "无主题";
        }
        if (body == null || body.trim().isEmpty()) {
            return "邮件正文不能为空";
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to.trim());
            helper.setSubject(subject.trim());
            helper.setText(body.trim(), false);

            mailSender.send(message);

            log.info("【发送邮件】to={}, subject={}", to, subject);
            return "✅ 邮件已成功发送到 " + to.trim();

        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error("【发送邮件】失败 to={}", to, e);
            return "❌ 邮件发送失败：" + e.getMessage();
        }
    }
}
