package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.service.FriendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/friend")
public class FriendController {

    @Autowired
    private FriendService friendService;

    @PostMapping("/apply")
    public Result<?> apply(@RequestAttribute("uid") Long uid, @RequestParam Long to) {
        try {
            friendService.apply(uid, to);
            return Result.ok("已发送好友申请");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/accept")
    public Result<?> accept(@RequestAttribute("uid") Long uid, @RequestParam Long from) {
        try {
            friendService.accept(uid, from);
            return Result.ok("已添加好友");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/reject")
    public Result<?> reject(@RequestAttribute("uid") Long uid, @RequestParam Long from) {
        friendService.reject(uid, from);
        return Result.ok("已拒绝申请");
    }

    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        return Result.ok(friendService.list(uid));
    }

    @GetMapping("/requests")
    public Result<?> requests(@RequestAttribute("uid") Long uid) {
        return Result.ok(friendService.requests(uid));
    }

    @DeleteMapping
    public Result<?> delete(@RequestAttribute("uid") Long uid, @RequestParam Long id) {
        friendService.delete(uid, id);
        return Result.ok("已删除好友");
    }

    @PostMapping("/block")
    public Result<?> block(@RequestAttribute("uid") Long uid, @RequestParam Long friendId) {
        try {
            friendService.block(uid, friendId);
            return Result.ok("已拉黑");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/unblock")
    public Result<?> unblock(@RequestAttribute("uid") Long uid, @RequestParam Long friendId) {
        friendService.unblock(uid, friendId);
        return Result.ok("已解除拉黑");
    }

    /** 设置好友备注（remark 可空，传空表示清除备注） */
    @PostMapping("/remark")
    public Result<?> remark(@RequestAttribute("uid") Long uid,
                            @RequestParam Long friendId,
                            @RequestParam(required = false) String remark) {
        try {
            friendService.setRemark(uid, friendId, remark);
            return Result.ok("已更新备注");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 在「我添加的用户」中按账号 / 用户名搜索好友（≥6 位门槛由前端控制） */
    @GetMapping("/search")
    public Result<?> searchMy(@RequestAttribute("uid") Long uid, @RequestParam String kw) {
        return Result.ok(friendService.searchMyFriends(uid, kw));
    }
}
