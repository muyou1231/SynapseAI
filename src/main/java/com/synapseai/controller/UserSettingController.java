package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.entity.UserSetting;
import com.synapseai.service.UserSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户开关型设置（目前承载 ③ 隐身阅读，后续新功能开关继续在此扩展）。
 */
@RestController
@RequestMapping("/api/setting")
public class UserSettingController {

    @Autowired
    private UserSettingService settingService;

    /** 我的设置（不存在则按默认值返回，并顺带落一行默认记录） */
    @GetMapping("")
    public Result<?> get(@RequestAttribute("uid") Long uid) {
        UserSetting s = settingService.getOrCreate(uid);
        Map<String, Object> m = new HashMap<>();
        m.put("ghostRead", Boolean.TRUE.equals(s.getGhostRead()));
        return Result.ok(m);
    }

    /** 设置隐身阅读：body { enabled: true/false }。开启后自己阅读消息不会向对方推送已读回执。 */
    @PostMapping("/ghost-read")
    public Result<?> setGhostRead(@RequestAttribute("uid") Long uid, @RequestBody Map<String, Object> body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        settingService.setGhostRead(uid, enabled);
        return Result.ok(enabled ? "已开启隐身阅读" : "已关闭隐身阅读");
    }
}
