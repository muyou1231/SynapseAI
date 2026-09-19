package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.LoginRequest;
import com.synapseai.dto.SwitchRequest;
import com.synapseai.entity.User;
import com.synapseai.dto.ProfileUpdateDto;
import com.synapseai.dto.RegisterRequest;
import com.synapseai.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterRequest req) {
        try {
            return Result.ok(userService.register(req));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/login")
    public Result<?> login(@RequestBody LoginRequest req) {
        try {
            return Result.ok(userService.login(req));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/me")
    public Result<?> me(@RequestAttribute("uid") Long uid) {
        return Result.ok(userService.getSafeUser(uid));
    }

    /** 退出登录：当前用户置为离线并广播给好友 */
    @PostMapping("/logout")
    public Result<?> logout(@RequestAttribute("uid") Long uid) {
        userService.setOnline(uid, false);
        return Result.ok("ok");
    }

    /** 标记当前用户为在线（页面刷新恢复会话时调用，让好友看到自己上线） */
    @PostMapping("/online")
    public Result<?> online(@RequestAttribute("uid") Long uid) {
        userService.setOnline(uid, true);
        return Result.ok("ok");
    }

    /** 切换账号：旧账号下线、新账号上线，返回新账号 token + 资料 */
    @PostMapping("/switch")
    public Result<?> switchAccount(@RequestAttribute("uid") Long uid, @RequestBody SwitchRequest req) {
        try {
            return Result.ok(userService.switchAccount(uid, req));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/search")
    public Result<?> search(@RequestAttribute("uid") Long uid, @RequestParam String kw) {
        List<Map<String, Object>> list = userService.search(kw);
        return Result.ok(list);
    }

    /** 搜索可添加的 AI 助手（按账号/名称匹配 role=AI 账户，排除默认助手与自己，已加为好友的标记 isFriend） */
    @GetMapping("/search/assistant")
    public Result<?> searchAssistant(@RequestAttribute("uid") Long uid, @RequestParam String kw) {
        List<Map<String, Object>> list = userService.searchAssistant(kw, uid);
        return Result.ok(list);
    }

    /** 更新当前登录用户的个人资料（性别/年龄/生日/宗教信仰/学历/昵称/头像） */
    @PostMapping("/profile")
    public Result<?> updateProfile(@RequestAttribute("uid") Long uid, @RequestBody ProfileUpdateDto dto) {
        try {
            return Result.ok(userService.updateProfile(uid, dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 查看任意用户的资料（按字段可见范围 + 好友关系过滤，本人返回全部） */
    @GetMapping("/{id}/profile")
    public Result<?> profile(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        try {
            return Result.ok(userService.getProfileView(id, uid));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
