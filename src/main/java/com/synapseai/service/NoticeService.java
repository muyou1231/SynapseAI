package com.synapseai.service;

import com.synapseai.common.TokenStore;
import com.synapseai.dto.NoticePublishReq;
import com.synapseai.entity.Notice;
import com.synapseai.entity.User;
import com.synapseai.mapper.NoticeMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知服务：管理员发布、用户查看/已读、管理端列表，以及向在线用户实时推送。
 */
@Service
public class NoticeService {

    @Autowired
    private NoticeMapper noticeMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private CacheManager cacheManager;

    /** 失效某个用户的通知中心缓存（userNotices），保持铃铛角标/历史与 DB 一致 */
    private void evictUserNotices(Long uid) {
        if (cacheManager == null) return;
        Cache c = cacheManager.getCache("userNotices");
        if (c != null) c.evict(uid);
    }

    /** 发布通知：计算过期时间 + 落库 + 向目标在线用户实时推送 WS(type=NOTICE) */
    public Notice publish(Long senderId, NoticePublishReq req) {
        if (req.getTitle() == null || req.getTitle().trim().isEmpty()) {
            throw new RuntimeException("通知标题不能为空");
        }
        String targetType = req.getTargetType() == null ? "ALL" : req.getTargetType().toUpperCase();
        if (!"ALL".equals(targetType) && !"SPECIFIED".equals(targetType)) {
            throw new RuntimeException("接收范围只能是 ALL 或 SPECIFIED");
        }
        Notice notice = new Notice();
        notice.setTitle(req.getTitle().trim());
        notice.setContent(req.getContent() == null ? "" : req.getContent());
        notice.setSenderId(senderId);
        notice.setTargetType(targetType);
        notice.setCategory("ADMIN");
        notice.setRefId(req.getRefId()); // 关联业务 id（如被打回的动态 id），用于点击通知跳转

        Integer duration = (req.getDurationMinutes() == null || req.getDurationMinutes() <= 0) ? null : req.getDurationMinutes();
        notice.setDurationMinutes(duration);
        notice.setExpireAt(duration == null ? null : LocalDateTime.now().plusMinutes(duration));

        // 阅读停留时长（秒）：必须大于该时长用户才能点击「我知道了」；<=0 视为不限制
        Integer wait = (req.getReadWaitSeconds() == null || req.getReadWaitSeconds() <= 0) ? 0 : req.getReadWaitSeconds();
        notice.setReadWaitSeconds(wait);

        if ("SPECIFIED".equals(targetType)) {
            if (req.getTargetIds() == null || req.getTargetIds().isEmpty()) {
                throw new RuntimeException("指定人通知必须选择至少一个接收人");
            }
            notice.setTargetIds(req.getTargetIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        } else {
            notice.setTargetIds(null);
        }

        noticeMapper.insert(notice);
        pushLive(notice);
        // 指定人通知：立即失效对应接收人的通知缓存；全员(ALL)通知靠 30s TTL 兜底刷新
        if ("SPECIFIED".equals(targetType) && notice.getTargetIds() != null) {
            for (String s : notice.getTargetIds().split(",")) {
                try {
                    evictUserNotices(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) { }
            }
        }
        return notice;
    }

    /** 撤销（删除）一条通知：删除通知本身及其全部已读记录，并让受影响用户的通知缓存失效、实时刷新。 */
    public void revoke(Long noticeId) {
        Notice n = noticeMapper.selectById(noticeId);
        if (n == null) return;
        noticeMapper.deleteReadByNoticeId(noticeId);
        noticeMapper.deleteById(noticeId);
        // 失效缓存：ALL 通知影响所有在线用户；SPECIFIED 仅影响指定接收人；离线用户靠 30s TTL 兜底
        if ("ALL".equals(n.getTargetType())) {
            for (Long uid : tokenStore.onlineUserIds()) evictUserNotices(uid);
        } else if (n.getTargetIds() != null) {
            for (String s : n.getTargetIds().split(",")) {
                try { evictUserNotices(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        // 向受影响在线用户推送 NOTICE_NEW，使其打开的通知中心/铃铛角标立即刷新（被撤销的通知消失）
        pushRefresh(n.getTargetType(), n.getTargetIds());
    }

    /** 向指定范围在线用户推送「通知有变更」信号（不弹窗，仅刷新角标/列表） */
    private void pushRefresh(String targetType, String targetIds) {
        Set<Long> targets = new LinkedHashSet<>();
        if ("ALL".equals(targetType)) {
            targets.addAll(tokenStore.onlineUserIds());
        } else if (targetIds != null) {
            for (String s : targetIds.split(",")) {
                try { targets.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        for (Long uid : targets) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "NOTICE_NEW");
            messagingTemplate.convertAndSend("/topic/user/" + uid, msg);
        }
    }

    /**
     * 针对单个用户的业务通知（如朋友圈评论/回复）：落库一条 SPECIFIED 通知（持久化→可标红/进历史），
     * 并推送轻量 NOTICE_NEW 让在线用户实时刷新铃铛角标（不弹模态框，避免刷屏）。
     */
    public Notice createUserNotice(Long senderId, Long targetUserId, String title, String content, Long refId) {
        return createUserNotice(senderId, targetUserId, title, content, refId, "COMMENT");
    }

    /** 带 category 的重载：支持点赞(LIKE)等非评论类业务通知，其余逻辑同 createUserNotice */
    public Notice createUserNotice(Long senderId, Long targetUserId, String title, String content, Long refId, String category) {
        Notice n = new Notice();
        n.setTitle(title == null ? "新消息" : title);
        n.setContent(content == null ? "" : content);
        n.setSenderId(senderId);
        n.setTargetType("SPECIFIED");
        n.setCategory(category == null ? "COMMENT" : category);
        n.setReadWaitSeconds(0); // 评论类通知无阅读时长要求；notice.read_wait_seconds 为 NOT NULL，必须显式赋值
        n.setRefId(refId); // 关联动态 id：点击通知可跳转到对应朋友圈内容
        n.setTargetIds(String.valueOf(targetUserId));
        n.setDurationMinutes(null);
        n.setExpireAt(null);
        noticeMapper.insert(n);
        evictUserNotices(targetUserId); // 新通知立即使该用户铃铛/历史失效，保证及时出现
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "NOTICE_NEW");
        msg.put("id", n.getId());
        messagingTemplate.convertAndSend("/topic/user/" + targetUserId, msg);
        return n;
    }

    /** 向目标用户实时推送（在线才推；离线用户登录时会由 pending 拉取） */
    private void pushLive(Notice notice) {
        Set<Long> targets = new LinkedHashSet<>();
        if ("ALL".equals(notice.getTargetType())) {
            targets.addAll(tokenStore.onlineUserIds());
        } else if (notice.getTargetIds() != null) {
            for (String s : notice.getTargetIds().split(",")) {
                try { targets.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        for (Long uid : targets) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "NOTICE");
            msg.put("id", notice.getId());
            msg.put("title", notice.getTitle());
            msg.put("content", notice.getContent());
            msg.put("refId", notice.getRefId()); // 关联业务 id（被打回的动态 id），用于点击弹窗跳转到对应内容
            msg.put("targetType", notice.getTargetType());
            msg.put("createTime", notice.getCreateTime() == null ? null : notice.getCreateTime().toString());
            msg.put("readWaitSeconds", notice.getReadWaitSeconds() == null ? 0 : notice.getReadWaitSeconds());
            messagingTemplate.convertAndSend("/topic/user/" + uid, msg);
        }
    }

    /** 用户通知中心：面向该用户的全部通知（含已过期），附带已读标记。
     *  通知永久保留，用户可随时查看历史；是否自动弹窗由 pending() 的窗口约束决定。 */
    @Cacheable(value = "userNotices", key = "#uid")
    public List<Map<String, Object>> listForUser(Long uid) {
        List<Notice> list = noticeMapper.selectForUser(uid);
        Set<Long> readIds = new LinkedHashSet<>(noticeMapper.selectReadNoticeIds(uid));
        return list.stream().map(n -> toMap(n, readIds.contains(n.getId()))).collect(Collectors.toList());
    }

    /** 待弹窗：未过期、面向该用户、且未读 的通知 */
    public List<Map<String, Object>> pending(Long uid) {
        List<Notice> list = noticeMapper.selectPending(uid);
        return list.stream().map(n -> toMap(n, false)).collect(Collectors.toList());
    }

    /** 标记已读（可批量） */
    @CacheEvict(value = "userNotices", key = "#uid")
    public void markRead(Long uid, List<Long> noticeIds) {
        if (noticeIds == null) return;
        for (Long id : noticeIds) {
            if (id != null) noticeMapper.insertRead(uid, id);
        }
    }

    /** 管理端：通知管理页列表（仅管理员发布 ADMIN 类；用户通知 COMMENT 由系统自动生成，不在管理页显示） */
    public List<Map<String, Object>> adminList() {
        List<Notice> list = noticeMapper.selectAdminList();
        return list.stream().map(n -> {
            Map<String, Object> m = toMap(n, null);
            User u = userMapper.selectById(n.getSenderId());
            m.put("senderName", u == null ? ("管理员" + n.getSenderId()) : u.getNickname());
            m.put("targetIds", n.getTargetIds());
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> toMap(Notice n, Boolean read) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("targetType", n.getTargetType());
        m.put("category", n.getCategory());
        m.put("refId", n.getRefId()); // 关联业务 id（如朋友圈评论通知关联的 moment id），用于点击通知跳转
        m.put("durationMinutes", n.getDurationMinutes());
        m.put("readWaitSeconds", n.getReadWaitSeconds() == null ? 0 : n.getReadWaitSeconds());
        m.put("expireAt", n.getExpireAt() == null ? null : n.getExpireAt().toString());
        m.put("createTime", n.getCreateTime() == null ? null : n.getCreateTime().toString());
        if (read != null) m.put("read", read);
        return m;
    }
}
