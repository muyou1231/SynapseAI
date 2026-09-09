package com.moyo.springchat.service;

import com.moyo.springchat.dto.MessageSearchItem;
import com.moyo.springchat.dto.WsMessage;
import com.moyo.springchat.entity.ChatGroup;
import com.moyo.springchat.entity.Message;
import com.moyo.springchat.entity.User;
import com.moyo.springchat.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MessageService {

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupService groupService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public WsMessage save(Long senderId, String type, String content, String targetType, Long targetId, Boolean urgent) {
        return save(senderId, type, content, targetType, targetId, urgent, null);
    }

    /**
     * 落库一条消息。
     *
     * @param bombSeconds ⑨ 消息炸弹倒计时秒数；null 或 &lt;=0 表示普通消息。
     *                    仅单聊文本消息生效（由调用方保证），炸弹到期未获回复会被定时任务引爆。
     */
    public WsMessage save(Long senderId, String type, String content, String targetType, Long targetId,
                          Boolean urgent, Integer bombSeconds) {
        Message m = new Message();
        m.setSenderId(senderId);
        m.setType(type);
        m.setContent(content);
        m.setTargetType(targetType);
        m.setTargetId(targetId);
        m.setUrgent(Boolean.TRUE.equals(urgent));
        if (bombSeconds != null && bombSeconds > 0) {
            m.setBombSeconds(bombSeconds);
            m.setBombDeadline(LocalDateTime.now().plusSeconds(bombSeconds));
            m.setBombStatus("PENDING");
        }
        messageMapper.insert(m);
        return toWs(m);
    }

    public WsMessage toWs(Message m) {
        User sender = userService.getById(m.getSenderId());
        WsMessage w = new WsMessage();
        w.setId(m.getId());
        w.setSenderId(m.getSenderId());
        w.setSenderNickname(sender != null
                ? (sender.getNickname() != null ? sender.getNickname() : sender.getUsername())
                : "未知用户");
        w.setSenderAvatar(sender != null ? sender.getAvatar() : null);
        w.setType(m.getType());
        w.setContent(m.getContent());
        w.setTargetType(m.getTargetType());
        w.setTargetId(m.getTargetId());
        w.setCreateTime(m.getCreateTime().format(FMT));
        w.setRecalled(Boolean.TRUE.equals(m.getRecalled()));
        w.setRead(Boolean.TRUE.equals(m.getRead()));
        w.setUrgent(Boolean.TRUE.equals(m.getUrgent()));
        w.setDeleted(Boolean.TRUE.equals(m.getDeleted()));
        // ⑨ 消息炸弹：倒计时秒数 / 截止时间 / 状态，普通消息均为 null
        w.setBombSeconds(m.getBombSeconds());
        w.setBombDeadline(m.getBombDeadline() == null ? null : m.getBombDeadline().format(FMT));
        w.setBombStatus(m.getBombStatus());
        return w;
    }

    /**
     * 单聊历史消息（分页）。
     * 数据库按 create_time desc 取（最新的在前），返回时反转为正序（旧的在前）便于前端从上到下渲染。
     * 返回结果含 hasMore 标记，前端据此决定是否继续上滑加载。
     */
    public WsMessage.HistoryResult historyUser(Long a, Long b, int page, int size) {
        int total = messageMapper.countUserMessages(a, b);
        List<Message> msgs = messageMapper.findUserMessages(a, b, page * size, size);
        Collections.reverse(msgs); // 反转为正序
        List<WsMessage> items = msgs.stream().map(this::toWs).collect(Collectors.toList());
        boolean hasMore = (page + 1) * size < total;
        return new WsMessage.HistoryResult(items, total, hasMore);
    }

    public WsMessage.HistoryResult historyGroup(Long groupId, int page, int size) {
        int total = messageMapper.countGroupMessages(groupId);
        List<Message> msgs = messageMapper.findGroupMessages(groupId, page * size, size);
        Collections.reverse(msgs);
        List<WsMessage> items = msgs.stream().map(this::toWs).collect(Collectors.toList());
        boolean hasMore = (page + 1) * size < total;
        return new WsMessage.HistoryResult(items, total, hasMore);
    }

    public WsMessage lastUser(Long a, Long b) {
        Message m = messageMapper.findLastUserMessage(a, b);
        return m == null ? null : toWs(m);
    }

    /** 断线重连补拉：返回该会话 id 大于 afterId 的消息（升序），好友/群成员鉴权已在 Controller 完成 */
    public List<WsMessage> syncAfter(Long uid, String targetType, Long targetId, Long afterId) {
        if (afterId == null) afterId = 0L;
        List<Message> msgs = messageMapper.selectAfter(uid, targetType, targetId, afterId);
        return msgs.stream().map(this::toWs).collect(Collectors.toList());
    }

    public WsMessage lastGroup(Long groupId) {
        Message m = messageMapper.findLastGroupMessage(groupId);
        return m == null ? null : toWs(m);
    }

    /** 自建会话（SESSION）历史消息（分页，倒序取 -> 前端再反转为正序） */
    public WsMessage.HistoryResult historySession(Long uid, Long sessionId, int page, int size) {
        int total = messageMapper.countSessionMessages(uid, sessionId);
        List<Message> msgs = messageMapper.findSessionMessages(uid, sessionId, page * size, size);
        Collections.reverse(msgs);
        List<WsMessage> items = msgs.stream().map(this::toWs).collect(Collectors.toList());
        boolean hasMore = (page + 1) * size < total;
        return new WsMessage.HistoryResult(items, total, hasMore);
    }

    /** 自建会话最后一条消息 */
    public WsMessage lastSession(Long uid, Long sessionId) {
        Message m = messageMapper.findLastSessionMessage(uid, sessionId);
        return m == null ? null : toWs(m);
    }

    /** 自建会话断线补拉 */
    public List<WsMessage> syncSession(Long uid, Long sessionId, Long afterId) {
        if (afterId == null) afterId = 0L;
        List<Message> msgs = messageMapper.selectSessionAfter(uid, sessionId, afterId);
        return msgs.stream().map(this::toWs).collect(Collectors.toList());
    }

    /**
     * 标记某会话中「发给当前用户」的未读消息为已读，返回被标记为已读的消息 id 列表。
     * 仅对单聊（USER）有效；群聊不展示已读回执，调用方传入 GROUP 时返回空列表。
     */
    public List<Long> markRead(Long uid, String targetType, Long targetId) {
        if (!"USER".equals(targetType)) {
            return Collections.emptyList();
        }
        List<Message> unread = messageMapper.findUnread("USER", uid, targetId);
        if (unread.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>();
        for (Message m : unread) {
            m.setRead(true);
            messageMapper.updateById(m);
            ids.add(m.getId());
        }
        return ids;
    }

    /** 软删除消息（仅发送者本人可删） */
    public boolean softDelete(Long messageId, Long uid) {
        Message m = messageMapper.selectById(messageId);
        if (m == null) return false;
        if (!m.getSenderId().equals(uid)) return false;
        if (Boolean.TRUE.equals(m.getDeleted())) return true; // 已删除视为成功
        messageMapper.softDelete(messageId);
        return true;
    }

    /**
     * 聊天记录搜索（仿微信）：按关键字搜「我参与的会话」中的文本消息。
     * 返回结果附带所属会话的可读名称，便于前端直接展示与跳转定位。
     */
    public List<MessageSearchItem> search(Long uid, String keyword, String targetType, Long targetId, int page, int size) {
        List<Message> msgs = messageMapper.searchMyMessages(uid, keyword, targetType, targetId, page * size, size);
        List<MessageSearchItem> out = new ArrayList<>();
        for (Message m : msgs) {
            MessageSearchItem it = new MessageSearchItem();
            it.setMessage(toWs(m));
            String convType = m.getTargetType();
            Long convId;
            String convName;
            if ("USER".equals(convType)) {
                convId = m.getSenderId().equals(uid) ? m.getTargetId() : m.getSenderId();
                User u = userService.getById(convId);
                convName = u != null
                        ? (u.getNickname() != null ? u.getNickname() : u.getUsername())
                        : "未知用户";
            } else {
                convId = m.getTargetId();
                ChatGroup g = groupService.getGroup(convId);
                convName = g != null ? g.getName() : "群聊";
            }
            it.setConvType(convType);
            it.setConvId(convId);
            it.setConvName(convName);
            out.add(it);
        }
        return out;
    }
}
