package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.NoticePublishReq;
import com.synapseai.entity.ChatGroup;
import com.synapseai.entity.GroupMember;
import com.synapseai.entity.Message;
import com.synapseai.entity.Moment;
import com.synapseai.entity.Notice;
import com.synapseai.entity.User;
import com.synapseai.mapper.FriendshipMapper;
import com.synapseai.mapper.GroupMapper;
import com.synapseai.mapper.GroupMemberMapper;
import com.synapseai.mapper.MessageMapper;
import com.synapseai.mapper.MomentMapper;
import com.synapseai.mapper.UserMapper;
import com.synapseai.service.MomentService;
import com.synapseai.service.NoticeService;
import com.synapseai.service.ReviewConfigService;
import com.synapseai.service.UserService;
import com.synapseai.service.AiAssistantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台接口：所有接口校验当前登录用户 role=ADMIN，非管理员返回错误。
 * 前端 admin.html 通过这些接口管理用户、审计聊天、查看朋友圈与群聊等。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private GroupMapper groupMapper;
    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private FriendshipMapper friendshipMapper;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private MomentService momentService;
    @Autowired
    private ReviewConfigService reviewConfigService;
    @Autowired
    private GroupMemberMapper groupMemberMapper;
    @Autowired
    private AiAssistantService aiAssistantService;

    /** 统一异常处理：业务异常（含权限不足）以 Result.error 形式返回 */
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException e) {
        return Result.error(e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    /** 校验管理员权限，非管理员抛出异常 */
    private void assertAdmin(Long uid) {
        User u = userMapper.selectById(uid);
        if (u == null || !"ADMIN".equals(u.getRole())) {
            throw new RuntimeException("无权限：需要管理员身份");
        }
    }

    private String nicknameOf(Long uid) {
        if (uid == null) return null;
        User u = userMapper.selectById(uid);
        return u == null ? ("用户" + uid) : u.getNickname();
    }

    private String groupNameOf(Long gid) {
        ChatGroup g = groupMapper.selectById(gid);
        return g == null ? ("群" + gid) : g.getName();
    }

    private String avatarOf(Long uid) {
        if (uid == null) return null;
        User u = userMapper.selectById(uid);
        return u == null ? null : u.getAvatar();
    }

    private Map<String, Object> toAdminUser(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("account", u.getAccount());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("role", u.getRole());
        m.put("online", u.getOnline());
        m.put("frozen", Boolean.TRUE.equals(u.getFrozen()));
        m.put("createTime", u.getCreateTime());
        return m;
    }

    /** 检查当前登录用户是否为管理员（前端据此显示/隐藏管理员入口） */
    @GetMapping("/check")
    public Result<?> check(@RequestAttribute("uid") Long uid) {
        User u = userMapper.selectById(uid);
        if (u == null) return Result.error("用户不存在");
        return Result.ok("ADMIN".equals(u.getRole()));
    }

    /** 用户列表（支持按账号/用户名/昵称搜索） */
    @GetMapping("/users")
    public Result<?> users(@RequestAttribute("uid") Long uid, @RequestParam(required = false) String kw) {
        assertAdmin(uid);
        List<User> list = (kw == null || kw.isBlank())
                ? userMapper.selectAll()
                : userMapper.findByUsernameContainingOrNicknameContainingOrAccountContaining(kw, kw, kw);
        return Result.ok(list.stream().map(this::toAdminUser).collect(Collectors.toList()));
    }

    /** 冻结账号：冻结后向该用户推送一条「账号已被冻结」通知（含原因，永久保留）。
     *  冻结用户仅可查看通知/通讯/消息，其他行为由 FrozenGuardInterceptor 拦截。不能冻结自己的管理员账号。 */
    @PostMapping("/users/{id}/freeze")
    @org.springframework.cache.annotation.CacheEvict(value = "userSafe", key = "#id")
    public Result<?> freeze(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id,
                            @RequestBody(required = false) java.util.Map<String, String> body) {
        assertAdmin(uid);
        if (id.equals(uid)) return Result.error("不能冻结自己的管理员账号");
        userMapper.updateFrozen(id, true);
        String reason = (body != null && body.get("reason") != null) ? body.get("reason").trim() : "";
        if (reason.isEmpty()) reason = "您的账号已被管理员冻结。";
        NoticePublishReq req = new NoticePublishReq();
        req.setTitle("账号已被冻结");
        req.setContent("冻结原因：" + reason + "\n冻结期间您仅可查看通知、通讯与消息，其他功能已禁用。");
        req.setTargetType("SPECIFIED");
        req.setTargetIds(java.util.List.of(id));
        req.setDurationMinutes(null); // 永久保留
        noticeService.publish(uid, req);
        return Result.ok("已冻结并通知用户");
    }

    /** 解冻账号：解冻后向该用户推送「账号已解冻」通知 */
    @PostMapping("/users/{id}/unfreeze")
    @org.springframework.cache.annotation.CacheEvict(value = "userSafe", key = "#id")
    public Result<?> unfreeze(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        assertAdmin(uid);
        userMapper.updateFrozen(id, false);
        NoticePublishReq req = new NoticePublishReq();
        req.setTitle("账号已解冻");
        req.setContent("您的账号已恢复正常，所有功能均可使用。");
        req.setTargetType("SPECIFIED");
        req.setTargetIds(java.util.List.of(id));
        req.setDurationMinutes(null);
        noticeService.publish(uid, req);
        return Result.ok("已解冻并通知用户");
    }

    /** 强制下线：置离线、移除其全部登录令牌、实时推送 KICK（不能对自己操作） */
    @PostMapping("/users/{id}/offline")
    public Result<?> offline(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        assertAdmin(uid);
        if (id.equals(uid)) return Result.error("不能强制下线自己的管理员账号");
        userService.forceOffline(id);
        return Result.ok("已强制下线");
    }

    /** 系统统计概览 */
    @GetMapping("/stats")
    public Result<?> stats(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("userTotal", userMapper.count());
        s.put("onlineCount", userMapper.countOnline());
        s.put("frozenCount", userMapper.countFrozen());
        s.put("messageTotal", messageMapper.countNormal());
        s.put("groupTotal", groupMapper.count());
        s.put("momentTotal", momentMapper.count());
        s.put("friendshipTotal", friendshipMapper.count());
        return Result.ok(s);
    }

    /**
     * 查看聊天内容（审计）：
     *  - targetType=USER & userId=A & peerId=B → A 与 B 的私聊
     *  - targetType=USER & userId=A           → A 参与的所有私聊
     *  - targetType=GROUP & groupId=G         → G 群的所有消息
     *  - keyword                                → 按消息内容模糊搜索（跨全部）
     */
    @GetMapping("/messages")
    public Result<?> messages(@RequestAttribute("uid") Long uid,
                              @RequestParam(required = false) String targetType,
                              @RequestParam(required = false) Long userId,
                              @RequestParam(required = false) Long peerId,
                              @RequestParam(required = false) Long groupId,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        assertAdmin(uid);
        int offset = Math.max(page, 0) * size;
        List<Message> msgs = messageMapper.adminFind(targetType, userId, peerId, groupId, keyword, offset, size);
        List<Map<String, Object>> data = msgs.stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getId());
            mm.put("type", m.getType());
            mm.put("content", m.getContent());
            mm.put("targetType", m.getTargetType());
            mm.put("targetId", m.getTargetId());
            mm.put("senderId", m.getSenderId());
            mm.put("senderName", nicknameOf(m.getSenderId()));
            mm.put("senderAvatar", avatarOf(m.getSenderId()));
            mm.put("targetName", "GROUP".equals(m.getTargetType()) ? groupNameOf(m.getTargetId()) : nicknameOf(m.getTargetId()));
            mm.put("read", m.getRead());
            mm.put("recalled", m.getRecalled());
            mm.put("urgent", m.getUrgent());
            mm.put("createTime", m.getCreateTime());
            return mm;
        }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 查看全部朋友圈动态 */
    @GetMapping("/moments")
    public Result<?> moments(@RequestAttribute("uid") Long uid,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        assertAdmin(uid);
        int offset = Math.max(page, 0) * size;
        List<Map<String, Object>> data = momentMapper.selectAll().stream()
                .skip(offset).limit(size).map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id", m.getId());
                    mm.put("userId", m.getUserId());
                    mm.put("authorName", nicknameOf(m.getUserId()));
                    mm.put("content", m.getContent());
                    mm.put("images", m.getImages());
                    mm.put("visibility", m.getVisibility());
                    mm.put("status", m.getStatus() == null ? "NORMAL" : m.getStatus());
                    mm.put("rejectReason", m.getRejectReason());
                    mm.put("createTime", m.getCreateTime());
                    return mm;
                }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 查看全部群聊 */
    @GetMapping("/groups")
    public Result<?> groups(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        List<Map<String, Object>> data = groupMapper.selectAll().stream().map(g -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", g.getId());
            mm.put("name", g.getName());
            mm.put("ownerId", g.getOwnerId());
            mm.put("ownerName", nicknameOf(g.getOwnerId()));
            mm.put("deleted", g.getDeleted());
            mm.put("createTime", g.getCreateTime());
            return mm;
        }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 查看某个群聊的成员（含头像/昵称/角色/加入时间/在线状态） */
    @GetMapping("/groups/{id}/members")
    public Result<?> groupMembers(@RequestAttribute("uid") Long uid, @PathVariable("id") Long groupId) {
        assertAdmin(uid);
        List<GroupMember> members = groupMemberMapper.findByGroupId(groupId);
        List<Map<String, Object>> data = members.stream().map(gm -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            User u = userMapper.selectById(gm.getUserId());
            mm.put("userId", gm.getUserId());
            mm.put("role", gm.getRole());
            mm.put("joinTime", gm.getCreateTime());
            mm.put("nickname", u == null ? null : u.getNickname());
            mm.put("avatar", u == null ? null : u.getAvatar());
            mm.put("account", u == null ? null : u.getAccount());
            mm.put("online", u != null && Boolean.TRUE.equals(u.getOnline()));
            return mm;
        }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 发布通知（全员或指定人），可设置有效时长（分钟，0/空=永久）。发布后向在线目标实时推送 */
    @PostMapping("/notice")
    public Result<?> publishNotice(@RequestAttribute("uid") Long uid, @RequestBody NoticePublishReq req) {
        assertAdmin(uid);
        Notice n = noticeService.publish(uid, req);
        return Result.ok(n.getId());
    }

    /** 管理员查看全部已发通知 */
    @GetMapping("/notices")
    public Result<?> notices(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        return Result.ok(noticeService.adminList());
    }

    /** 撤销（删除）一条已发通知：通知将从用户端消失，已读记录一并清理 */
    @DeleteMapping("/notice/{id}")
    public Result<?> revokeNotice(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        assertAdmin(uid);
        noticeService.revoke(id);
        return Result.ok();
    }

    /** 查看某条朋友圈动态下的全部评论（管理端审计用） */
    @GetMapping("/moments/{id}/comments")
    public Result<?> momentComments(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId) {
        assertAdmin(uid);
        return Result.ok(momentService.getComments(momentId));
    }

    /** 打回（整改）某条朋友圈动态：置状态 REJECTED；打回原因由作者在其动态卡片上查看，不再单独发送弹窗通知 */
    @PostMapping("/moments/{id}/reject")
    public Result<?> rejectMoment(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId,
                                  @RequestBody(required = false) java.util.Map<String, String> body) {
        assertAdmin(uid);
        Moment m = momentMapper.selectById(momentId);
        if (m == null) return Result.error("动态不存在");
        String reason = (body != null && body.get("reason") != null) ? body.get("reason").trim() : "";
        if (reason.isEmpty()) reason = "内容不符合社区规范，请修改后重新发布。";
        momentService.reject(momentId, reason);
        return Result.ok("已打回，作者可在其动态卡片查看整改原因");
    }

    /** 审核通过：将待审核/被打回的动态转为正常并公开；仅人工审核(MANUAL)模式下进入待审核队列的动态需要此操作 */
    @PostMapping("/moments/{id}/approve")
    public Result<?> approveMoment(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId) {
        assertAdmin(uid);
        Moment m = momentMapper.selectById(momentId);
        if (m == null) return Result.error("动态不存在");
        momentService.approve(momentId);
        // 通知作者审核通过
        NoticePublishReq req = new NoticePublishReq();
        req.setTitle("您的动态已通过审核");
        req.setContent("您发布的一条动态已通过管理员审核，现在所有人都可以看到了。");
        req.setTargetType("SPECIFIED");
        req.setTargetIds(java.util.List.of(m.getUserId()));
        req.setDurationMinutes(null);
        req.setRefId(momentId);
        noticeService.publish(uid, req);
        return Result.ok("已通过审核并通知作者");
    }

    /** 撤回打回：恢复动态为正常状态 */
    @PostMapping("/moments/{id}/restore")
    public Result<?> restoreMoment(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId) {
        assertAdmin(uid);
        momentService.restore(momentId);
        return Result.ok("已撤回打回，动态恢复正常");
    }

    /** 管理员彻底删除某条动态（用于「打回列表」清理）：级联清理评论与点赞，不可恢复 */
    @DeleteMapping("/moments/{id}")
    public Result<?> deleteMoment(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId) {
        assertAdmin(uid);
        Moment m = momentMapper.selectById(momentId);
        if (m == null) return Result.error("动态不存在");
        momentService.delete(m.getUserId(), momentId);
        return Result.ok("已删除该动态");
    }

    /** 审核队列：列出动态供管理员复核。
     *  filter 取值：
     *   - pending          : 仅待审核(PENDING)队列（人工审核模式 / AI 打回后待人工复审）——默认
     *   - all              : 全部经过 AI 审核判定的内容（含 AI 通过已公开 NORMAL 与 AI 打回 PENDING）
     *   - ai_fail          : 仅 AI 打回的内容（ai_review='FAIL'）
     *   - manual_rejected  : 仅人工打回的内容（status=REJECTED 且 manual_review=1），只可查看/删除，不可重复打回
     */
    @GetMapping("/review")
    public Result<?> reviewList(@RequestAttribute("uid") Long uid,
                                @RequestParam(value = "filter", defaultValue = "pending") String filter) {
        assertAdmin(uid);
        List<Moment> moments;
        if ("all".equalsIgnoreCase(filter)) {
            moments = momentMapper.selectAiReviewed();
        } else if ("ai_fail".equalsIgnoreCase(filter)) {
            moments = momentMapper.selectAiFail();
        } else if ("manual_rejected".equalsIgnoreCase(filter)) {
            moments = momentMapper.selectManualRejected();
        } else {
            moments = momentMapper.selectPending();
        }
        List<Map<String, Object>> data = moments.stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getId());
            mm.put("userId", m.getUserId());
            mm.put("authorName", nicknameOf(m.getUserId()));
            mm.put("content", m.getContent());
            mm.put("images", m.getImages());
            mm.put("visibility", m.getVisibility());
            mm.put("status", m.getStatus() == null ? "PENDING" : m.getStatus());
            mm.put("rejectReason", m.getRejectReason());
            mm.put("aiReview", m.getAiReview());
            mm.put("aiSuggestion", m.getAiSuggestion());
            mm.put("manualReview", m.getManualReview() != null && m.getManualReview());
            mm.put("createTime", m.getCreateTime());
            return mm;
        }).collect(Collectors.toList());
        return Result.ok(data);
    }

    /** 读取当前审核模式（AUTO=自动审核 / MANUAL=人工审核） */
    @GetMapping("/review/mode")
    public Result<?> reviewMode(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        return Result.ok(reviewConfigService.getMode());
    }

    /** 设置审核模式：AUTO=自动审核(直接发布) / MANUAL=人工审核(内容先进入待审核队列) */
    @PostMapping("/review/mode")
    public Result<?> setReviewMode(@RequestAttribute("uid") Long uid, @RequestBody(required = false) java.util.Map<String, String> body) {
        assertAdmin(uid);
        String mode = body != null ? body.get("mode") : null;
        if (mode == null || mode.trim().isEmpty()) return Result.error("请指定 mode");
        try {
            reviewConfigService.setMode(mode.trim());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        return Result.ok("审核模式已切换为 " + mode.trim().toUpperCase());
    }

    /* ============ AI 助手管理（role=AI 账户，仅管理员可增改） ============ */

    /** 列出全部 AI 助手（id 升序，id=1 为默认助手） */
    @GetMapping("/assistants")
    public Result<?> listAssistants(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        return Result.ok(aiAssistantService.listAssistants());
    }

    /** 生成一个全新的唯一账号（>=10 位），供新增/编辑 AI 助手时「生成账号」按钮使用 */
    @GetMapping("/generate-account")
    public Result<?> generateAccount(@RequestAttribute("uid") Long uid) {
        assertAdmin(uid);
        return Result.ok(userService.newAccount());
    }

    /** 新建 AI 助手：创建 role=AI 的 user 账户 + 配置行（受「每用户仅一个默认助手」约束，需谨慎） */
    @PostMapping("/assistants")
    public Result<?> createAssistant(@RequestAttribute("uid") Long uid, @RequestBody(required = false) com.synapseai.entity.AiAssistant body) {
        assertAdmin(uid);
        if (body == null) return Result.error("缺少参数");
        if (body.getName() == null || body.getName().isBlank()) return Result.error("请填写助手名称");
        return Result.ok(aiAssistantService.createAssistant(body));
    }

    /** 编辑指定 AI 助手：启用/禁用、状态、人设、展示名、头像、签名（双向同步到 user(AI) 账户） */
    @PutMapping("/assistants/{id}")
    public Result<?> updateAssistant(@RequestAttribute("uid") Long uid, @PathVariable("id") Integer id,
                                     @RequestBody(required = false) com.synapseai.entity.AiAssistant body) {
        assertAdmin(uid);
        if (body == null) return Result.error("缺少参数");
        return Result.ok(aiAssistantService.updateAssistantById(id, body));
    }

    /** 删除指定 AI 助手（默认助手 id=1 不可删；同时删除关联的 user(AI) 账户） */
    @DeleteMapping("/assistants/{id}")
    public Result<?> deleteAssistant(@RequestAttribute("uid") Long uid, @PathVariable("id") Integer id) {
        assertAdmin(uid);
        try {
            aiAssistantService.deleteAssistant(id);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        return Result.ok("已删除");
    }
}
