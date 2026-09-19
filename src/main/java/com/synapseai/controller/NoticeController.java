package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.NoticePublishReq;
import com.synapseai.service.NoticeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户端通知接口（需登录）。查看通知中心、待弹窗列表、标记已读。
 */
@RestController
@RequestMapping("/api/notice")
public class NoticeController {

    @Autowired
    private NoticeService noticeService;

    /** 通知中心：未过期且面向当前用户的通知（含已读标记） */
    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        return Result.ok(noticeService.listForUser(uid));
    }

    /** 待弹窗：未过期、面向当前用户、且未读 的通知（登录后弹窗用） */
    @GetMapping("/pending")
    public Result<?> pending(@RequestAttribute("uid") Long uid) {
        return Result.ok(noticeService.pending(uid));
    }

    /** 标记已读（批量）：body { noticeIds: [1,2,3] } */
    @PostMapping("/read")
    public Result<?> read(@RequestAttribute("uid") Long uid, @RequestBody Map<String, Object> body) {
        Object ids = body.get("noticeIds");
        List<Long> list;
        if (ids instanceof List) {
            list = ((List<?>) ids).stream()
                    .filter(Objects::nonNull)
                    .map(o -> Long.valueOf(o.toString()))
                    .collect(java.util.stream.Collectors.toList());
        } else if (body.get("noticeId") != null) {
            list = List.of(Long.valueOf(body.get("noticeId").toString()));
        } else {
            list = List.of();
        }
        noticeService.markRead(uid, list);
        return Result.ok("已标记已读");
    }
}
