package com.synapseai.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送（QQ 邮箱 SMTP）。配置见 application.yml 的 spring.mail.*。
 * 发送失败时抛出 RuntimeException，由调用方转成接口错误返回。
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    @Value("${app.verify-code.expire-seconds:300}")
    private int expireSeconds;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** 发送验证码邮件（注册 / 改密 / 登录 共用，按 type 调整文案） */
    public void sendCode(String to, String code, String type) {
        try {
            String purpose = "register".equals(type) ? "注册账号" : ("changepwd".equals(type) ? "修改密码" : "登录");
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject("微聊 · 邮箱验证码（" + purpose + "）");
            msg.setText("您正在使用邮箱验证码" + purpose + "，验证码为：" + code +
                    "（" + expireSeconds + " 秒内有效）。\n如非本人操作，请忽略本邮件。");
            mailSender.send(msg);
        } catch (Exception e) {
            // 配置错误或网络异常时记录日志并上抛，便于排查 SMTP/授权码问题
            log.warn("邮件发送失败（to={}, code={}）：{}", to, code, e.getMessage());
            throw new RuntimeException("邮件发送失败，请检查 spring.mail 配置（host/端口/授权码）：" + e.getMessage());
        }
    }
}
