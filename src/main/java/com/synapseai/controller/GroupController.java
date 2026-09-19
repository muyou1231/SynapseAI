package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/group")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @PostMapping("/create")
    public Result<?> create(@RequestAttribute("uid") Long uid,
                            @RequestParam String name,
                            @RequestParam(required = false) List<Long> memberIds) {
        try {
            return Result.ok(groupService.create(uid, name, memberIds));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/invite")
    public Result<?> invite(@RequestParam Long groupId, @RequestParam List<Long> userIds) {
        groupService.invite(groupId, userIds);
        return Result.ok("已邀请成员");
    }

    @PostMapping("/remove")
    public Result<?> remove(@RequestParam Long groupId, @RequestParam Long userId) {
        try {
            groupService.removeMember(groupId, userId);
            return Result.ok("已移除成员");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/quit")
    public Result<?> quit(@RequestAttribute("uid") Long uid, @RequestParam Long groupId) {
        groupService.quit(uid, groupId);
        return Result.ok("已退出群聊");
    }

    @PostMapping("/dissolve")
    public Result<?> dissolve(@RequestAttribute("uid") Long uid, @RequestParam Long groupId) {
        try {
            groupService.dissolve(uid, groupId);
            return Result.ok("群聊已解散");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<?> list(@RequestAttribute("uid") Long uid) {
        return Result.ok(groupService.myGroups(uid));
    }

    @GetMapping("/members")
    public Result<?> members(@RequestParam Long groupId) {
        return Result.ok(groupService.members(groupId));
    }
}
