package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.CapsuleCreateReq;
import com.synapseai.service.FriendService;
import com.synapseai.service.TimeCapsuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * ① 时间胶囊：写给未来的自己或指定好友，到期前加密封存，到期自动解锁并推送通知。
 */
@RestController
@RequestMapping("/api/capsule")
public class TimeCapsuleController {

    @Autowired
    private TimeCapsuleService capsuleService;
    @Autowired
    private FriendService friendService;

    /** 我相关的胶囊（我写的 + 别人写给我的），未解锁的不返回正文 */
    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        List<Map<String, Object>> list = capsuleService.listMine(uid);
        return Result.ok(list);
    }

    /** 创建胶囊：body { receiverId(可空=写给自己), content, openDate(yyyy-MM-dd，须晚于今天) } */
    @PostMapping("/create")
    public Result<?> create(@RequestAttribute("uid") Long uid, @RequestBody CapsuleCreateReq req) {
        if (req == null || req.getContent() == null || req.getContent().trim().isEmpty()) {
            return Result.error("胶囊内容不能为空");
        }
        LocalDate openDate;
        try {
            openDate = LocalDate.parse(req.getOpenDate().trim());
        } catch (DateTimeParseException | NullPointerException e) {
            return Result.error("开启日期格式不正确，应为 yyyy-MM-dd");
        }
        // 收件人必须已是好友（写给自己则 receiverId 为空，不做该校验）
        if (req.getReceiverId() != null && !friendService.isFriend(uid, req.getReceiverId())) {
            return Result.error("只能给好友寄时间胶囊");
        }
        try {
            Long id = capsuleService.create(uid, req.getReceiverId(), req.getContent(),
                    TimeCapsuleService.atStartOfDay(openDate));
            return Result.ok(id);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 查看单个胶囊详情（未解锁则不含正文） */
    @GetMapping("/{id}")
    public Result<?> view(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        try {
            return Result.ok(capsuleService.view(uid, id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
