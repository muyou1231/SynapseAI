package com.synapseai.common;

import com.synapseai.service.AiAssistantService;
import com.synapseai.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时确保管理员账号存在（角色 ADMIN），登录凭据来自 application.yml 的 app.admin.*。
 * 同时初始化 moyo AI 助手系统账户，并把它加为所有存量用户的好友。
 * 新注册用户会在 UserService.register 中自动添加助手好友。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    @Autowired
    private UserService userService;
    @Autowired
    private AiAssistantService aiAssistantService;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Value("${app.assistant.password:moyo@2024}")
    private String assistantPassword;

    @Override
    public void run(ApplicationArguments args) {
        userService.ensureAdmin(adminUsername, adminPassword);
        // 初始化 moyo 助手账户（role=AI），并缓存其 userId
        Long aid = userService.ensureAssistant(AiAssistantService.ASSISTANT_USERNAME, assistantPassword, "moyo助手");
        // 将默认助手配置(id=1)的 user_id 关联到底层 AI 账户（首次启动/存量数据修复）
        aiAssistantService.bindDefaultAssistantUser(aid);
        aiAssistantService.setAssistantId(aid);
        // 存量用户：批量把默认助手加为好友（新用户由 register 负责；默认按 is_default=1 定位）
        List<Long> all = userService.allUserIds();
        for (Long uid : all) {
            userService.addDefaultAssistantAsFriend(uid);
        }
    }
}
