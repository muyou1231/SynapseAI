package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.MessageSearchItem;
import com.synapseai.dto.WsMessage;
import com.synapseai.entity.Message;
import com.synapseai.entity.Pin;
import com.synapseai.entity.UrgentMute;
import com.synapseai.mapper.ChatSessionMapper;
import com.synapseai.mapper.ConversationMapper;
import com.synapseai.mapper.MessageMapper;
import com.synapseai.mapper.PinMapper;
import com.synapseai.mapper.UrgentMuteMapper;
import com.synapseai.service.FriendService;
import com.synapseai.service.GroupService;
import com.synapseai.service.MessageService;
import com.synapseai.service.UserSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private PinMapper pinMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private UrgentMuteMapper urgentMuteMapper;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private UserSettingService userSettingService;

    /** 从缓存/DB 返回的 Map 中取出 id 等数值时，兼容 JSON 反序列化把 Long 变成 Integer 的情况 */
    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @GetMapping("/history")
    public Result<?> history(@RequestAttribute("uid") Long uid,
                             @RequestParam String targetType,
                             @RequestParam Long targetId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "30") int size) {
        if ("USER".equals(targetType)) {
            if (!uid.equals(targetId) && !friendService.isFriend(uid, targetId)) {
                return Result.error("你们还不是好友");
            }
            return Result.ok(messageService.historyUser(uid, targetId, page, size));
        } else if ("GROUP".equals(targetType)) {
            if (!groupService.isMember(targetId, uid)) {
                return Result.error("你不在该群");
            }
            return Result.ok(messageService.historyGroup(targetId, page, size));
        } else if ("SESSION".equals(targetType)) {
            if (chatSessionMapper.selectByIdAndUser(targetId, uid) == null) {
                return Result.error("会话不存在或无权查看");
            }
            return Result.ok(messageService.historySession(uid, targetId, page, size));
        }
        return Result.error("不支持的会话类型");
    }

    /** 聊天记录搜索（仿微信）：按关键字搜「我参与的会话」中的文本消息。
     *  targetType+targetId 可限定在某个会话内；留空则搜「全部我的会话」。结果附带会话可读名称。 */
    @GetMapping("/search")
    public Result<?> search(@RequestAttribute("uid") Long uid,
                            @RequestParam String keyword,
                            @RequestParam(required = false) String targetType,
                            @RequestParam(required = false) Long targetId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "30") int size) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        keyword = keyword.trim();
        if (size > 100) size = 100;
        if (page < 0) page = 0;
        return Result.ok(messageService.search(uid, keyword, targetType, targetId, page, size));
    }

    /** 断线重连补拉：返回该会话里 id 大于 afterId 的消息（升序），用于把断线期间漏推的消息补齐。
     *  好友/群成员鉴权与 history 一致；afterId 缺省为 0（即返回全部可见消息）。 */
    @GetMapping("/sync")
    public Result<?> sync(@RequestAttribute("uid") Long uid,
                          @RequestParam String targetType,
                          @RequestParam Long targetId,
                          @RequestParam(required = false) Long afterId) {
        if ("USER".equals(targetType)) {
            if (!uid.equals(targetId) && !friendService.isFriend(uid, targetId)) {
                return Result.error("你们还不是好友");
            }
            return Result.ok(messageService.syncAfter(uid, "USER", targetId, afterId == null ? 0L : afterId));
        } else if ("GROUP".equals(targetType)) {
            if (!groupService.isMember(targetId, uid)) {
                return Result.error("你不在该群");
            }
            return Result.ok(messageService.syncAfter(uid, "GROUP", targetId, afterId == null ? 0L : afterId));
        } else if ("SESSION".equals(targetType)) {
            if (chatSessionMapper.selectByIdAndUser(targetId, uid) == null) {
                return Result.error("会话不存在或无权查看");
            }
            return Result.ok(messageService.syncSession(uid, targetId, afterId == null ? 0L : afterId));
        }
        return Result.error("不支持的会话类型");
    }

    /** 软删除消息：仅发送者本人可删，数据库 deleted=1 */
    @DeleteMapping("/{id}")
    public Result<?> deleteMessage(@RequestAttribute("uid") Long uid, @PathVariable Long id) {
        boolean ok = messageService.softDelete(id, uid);
        return ok ? Result.ok("已删除") : Result.error("删除失败：消息不存在或无权删除");
    }

    /** 删除整个会话：标记 conversation.deleted=1（持久化，刷新不再显示），消息记录保留；同时取消置顶 */
    @DeleteMapping("/conversation")
    public Result<?> deleteConversation(@RequestAttribute("uid") Long uid,
                                        @RequestParam String targetType,
                                        @RequestParam Long targetId) {
        conversationMapper.markDeleted(uid, targetType, targetId);
        // 删除会话后取消置顶
        pinMapper.deleteByUserIdAndTargetTypeAndTargetId(uid, targetType, targetId);
        return Result.ok("ok");
    }

    /** 恢复会话：从通讯录/群列表重新打开会话时调用，置 conversation.deleted=0（确保存在） */
    @PostMapping("/conversation/restore")
    public Result<?> restoreConversation(@RequestAttribute("uid") Long uid,
                                         @RequestParam String targetType,
                                         @RequestParam Long targetId) {
        conversationMapper.restore(uid, targetType, targetId);
        return Result.ok("ok");
    }

    /** 会话管理：显式创建空白会话（私人会话/笔记会话，仅本人可见） */
    @PostMapping("/session")
    public Result<?> createSession(@RequestAttribute("uid") Long uid,
                                   @RequestParam(defaultValue = "新会话") String title) {
        com.synapseai.entity.ChatSession s = new com.synapseai.entity.ChatSession();
        s.setUserId(uid);
        s.setTitle(title == null || title.trim().isEmpty() ? "新会话" : title.trim());
        chatSessionMapper.insert(s);
        Map<String, Object> data = new HashMap<>();
        data.put("id", s.getId());
        data.put("title", s.getTitle());
        return Result.ok(data);
    }

    /** 会话管理：删除自建会话（仅本人可删，会话下消息保留但会话入口消失） */
    @DeleteMapping("/session/{id}")
    public Result<?> deleteSession(@RequestAttribute("uid") Long uid, @PathVariable Long id) {
        int n = chatSessionMapper.deleteByIdAndUser(id, uid);
        if (n == 0) {
            return Result.error("会话不存在或无权删除");
        }
        // 同时取消置顶
        pinMapper.deleteByUserIdAndTargetTypeAndTargetId(uid, "SESSION", id);
        return Result.ok("ok");
    }

    /** 会话列表：好友 + 群，附带最后一条消息与置顶标记 */
    @GetMapping("/conversation/list")
    public Result<?> conversations(@RequestAttribute("uid") Long uid) {
        // 已删除的会话（conversation.deleted=1）不显示
        Set<String> hidden = new HashSet<>();
        for (Map<String, Object> d : conversationMapper.deletedTargets(uid)) {
            hidden.add(d.get("target_type") + ":" + d.get("target_id"));
        }

        List<Map<String, Object>> convs = new ArrayList<>();

        for (Map<String, Object> f : friendService.list(uid)) {
            if (Boolean.TRUE.equals(f.get("blocked"))) {
                continue; // 被拉黑的好友不进入会话列表
            }
            if (hidden.contains("USER:" + f.get("id"))) {
                continue; // 已删除的会话不显示
            }
            Map<String, Object> c = new HashMap<>();
            c.put("type", "USER");
            c.put("id", f.get("id"));
            c.put("name", f.get("nickname"));
            c.put("avatar", f.get("avatar"));
            c.put("online", f.get("online"));
            // AI 助手：透传 isAssistant + aiStatus，使会话列表头像按 AI 自身状态着色（在线绿/离线灰等）
            c.put("isAssistant", f.get("isAssistant"));
            if (Boolean.TRUE.equals(f.get("isAssistant"))) {
                c.put("aiStatus", f.get("aiStatus"));
            }
            c.put("lastMessage", messageService.lastUser(uid, toLong(f.get("id"))));
            c.put("pinned", pinMapper.existsByUserIdAndTargetTypeAndTargetId(uid, "USER", toLong(f.get("id"))));
            convs.add(c);
        }

        for (Map<String, Object> g : groupService.myGroups(uid)) {
            if (hidden.contains("GROUP:" + g.get("id"))) {
                continue; // 已删除的会话不显示
            }
            Map<String, Object> c = new HashMap<>();
            c.put("type", "GROUP");
            c.put("id", g.get("id"));
            c.put("name", g.get("name"));
            c.put("avatar", g.get("avatar"));
            c.put("deleted", g.get("deleted"));
            c.put("lastMessage", messageService.lastGroup(toLong(g.get("id"))));
            c.put("pinned", pinMapper.existsByUserIdAndTargetTypeAndTargetId(uid, "GROUP", toLong(g.get("id"))));
            convs.add(c);
        }
        // 置顶会话排在最前，其余保持原有顺序
        convs.sort((a, b) -> Boolean.compare(
                (Boolean) b.getOrDefault("pinned", false),
                (Boolean) a.getOrDefault("pinned", false)));

        // 用户自建会话（会话管理：显式创建/删除的私人会话），仅本人可见
        for (com.synapseai.entity.ChatSession s : chatSessionMapper.selectByUser(uid)) {
            Map<String, Object> c = new HashMap<>();
            c.put("type", "SESSION");
            c.put("id", s.getId());
            c.put("name", s.getTitle());
            c.put("avatar", null);
            c.put("online", null);
            c.put("lastMessage", messageService.lastSession(uid, s.getId()));
            c.put("pinned", pinMapper.existsByUserIdAndTargetTypeAndTargetId(uid, "SESSION", s.getId()));
            convs.add(c);
        }
        return Result.ok(convs);
    }

    /** 置顶 / 取消置顶某个会话 */
    @PostMapping("/pin")
    public Result<?> pin(@RequestAttribute("uid") Long uid,
                         @RequestParam String targetType,
                         @RequestParam Long targetId,
                         @RequestParam boolean pinned) {
        if (pinned) {
            if (!pinMapper.existsByUserIdAndTargetTypeAndTargetId(uid, targetType, targetId)) {
                Pin p = new Pin();
                p.setUserId(uid);
                p.setTargetType(targetType);
                p.setTargetId(targetId);
                pinMapper.insert(p);
            }
        } else {
            pinMapper.deleteByUserIdAndTargetTypeAndTargetId(uid, targetType, targetId);
        }
        return Result.ok("ok");
    }

    /** 撤回消息：仅发送者可撤回，且需在发送后 2 分钟内；撤回后实时通知会话成员 */
    @PostMapping("/recall")
    public Result<?> recall(@RequestAttribute("uid") Long uid, @RequestParam Long messageId) {
        Message m = messageMapper.selectById(messageId);
        if (m == null) {
            return Result.error("消息不存在");
        }
        if (!m.getSenderId().equals(uid)) {
            return Result.error("只能撤回自己发送的消息");
        }
        if (Boolean.TRUE.equals(m.getRecalled())) {
            return Result.error("消息已撤回");
        }
        if (LocalDateTime.now().isAfter(m.getCreateTime().plusMinutes(2))) {
            return Result.error("消息已超过 2 分钟，无法撤回");
        }
        m.setRecalled(true);
        messageMapper.updateById(m);

        WsMessage notice = new WsMessage();
        notice.setId(m.getId());
        notice.setType("RECALL");
        notice.setSenderId(m.getSenderId());
        notice.setTargetType(m.getTargetType());
        notice.setTargetId(m.getTargetId());
        notice.setRecalled(true);

        if ("USER".equals(m.getTargetType())) {
            messagingTemplate.convertAndSend("/topic/user/" + m.getTargetId(), notice);
            messagingTemplate.convertAndSend("/topic/user/" + uid, notice);
        } else if ("GROUP".equals(m.getTargetType())) {
            messagingTemplate.convertAndSend("/topic/group/" + m.getTargetId(), notice);
        }
        return Result.ok("ok");
    }

    /** 标记会话消息为已读（单聊有效）。标记后向消息发送方推送 READ 通知，使其显示「已读」 */
    @PostMapping("/read")
    public Result<?> read(@RequestAttribute("uid") Long uid,
                          @RequestParam String targetType,
                          @RequestParam Long targetId) {
        if ("USER".equals(targetType)) {
            if (!uid.equals(targetId) && !friendService.isFriend(uid, targetId)) {
                return Result.error("你们还不是好友");
            }
            List<Long> ids = messageService.markRead(uid, targetType, targetId);
            // ③ 隐身阅读：阅读方开启后仍更新自己的已读状态（便于多端同步），
            // 但不向对方推送已读回执，对方界面不会显示「已读」。
            boolean ghost = userSettingService.isGhostRead(uid);
            if (!ids.isEmpty() && !ghost) {
                WsMessage notice = new WsMessage();
                notice.setType("READ");
                notice.setSenderId(uid);          // 阅读者
                notice.setTargetType("USER");
                notice.setTargetId(targetId);     // 会话对方 = 原发送方，由其界面更新「已读」
                notice.setReadIds(ids);
                messagingTemplate.convertAndSend("/topic/user/" + targetId, notice);
            }
        }
        return Result.ok("ok");
    }

    /** 输入状态通知：单聊有效。focus 输入框时 typing=true，blur 或发送后 typing=false，
     *  后端仅将状态实时推送给会话对方，不落库。 */
    @PostMapping("/typing")
    public Result<?> typing(@RequestAttribute("uid") Long uid,
                            @RequestParam String targetType,
                            @RequestParam Long targetId,
                            @RequestParam boolean typing) {
        if ("USER".equals(targetType)) {
            if (!uid.equals(targetId) && !friendService.isFriend(uid, targetId)) {
                return Result.error("你们还不是好友");
            }
            WsMessage notice = new WsMessage();
            notice.setType("TYPING");
            notice.setSenderId(uid);
            notice.setTargetType("USER");
            notice.setTargetId(targetId);
            notice.setTyping(typing);
            messagingTemplate.convertAndSend("/topic/user/" + targetId, notice);
        }
        return Result.ok("ok");
    }

    /** 屏蔽 / 取消屏蔽某发送方的加急弹窗（单聊按好友、群聊按群成员，均按发送方 peer 处理） */
    @PostMapping("/urgent-mute")
    public Result<?> urgentMute(@RequestAttribute("uid") Long uid,
                                @RequestParam Long peerId,
                                @RequestParam boolean muted) {
        if (muted) {
            if (!urgentMuteMapper.existsByUserIdAndPeerId(uid, peerId)) {
                UrgentMute m = new UrgentMute();
                m.setUserId(uid);
                m.setPeerId(peerId);
                urgentMuteMapper.insert(m);
            }
        } else {
            urgentMuteMapper.deleteByUserIdAndPeerId(uid, peerId);
        }
        return Result.ok("ok");
    }

    /** 当前用户已屏蔽加急弹窗的发送方列表 */
    @GetMapping("/urgent-mute/list")
    public Result<?> urgentMuteList(@RequestAttribute("uid") Long uid) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UrgentMute m : urgentMuteMapper.findByUserId(uid)) {
            Map<String, Object> o = new HashMap<>();
            o.put("peerId", m.getPeerId());
            list.add(o);
        }
        return Result.ok(list);
    }
}
