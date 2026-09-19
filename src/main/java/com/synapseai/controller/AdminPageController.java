package com.synapseai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理后台独立入口：/admin 重定向到 admin.html（与普通用户端 index.html 完全分离）。
 * 普通用户即使打开该页面，也因无管理员权限而无法调用任何 /api/admin/* 接口。
 */
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String admin() {
        return "redirect:/admin.html";
    }
}
