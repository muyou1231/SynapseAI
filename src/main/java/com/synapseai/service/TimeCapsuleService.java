package com.synapseai.service;

import com.synapseai.entity.TimeCapsule;
import com.synapseai.mapper.TimeCapsuleMapper;
import com.synapseai.util.CapsuleCrypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ① 时间胶囊：写给未来的自己或指定好友，到期前正文以 AES-256-GCM 密文封存，到期后解锁可读。
 *
 * <p>可见性规则：
 * <ul>
 *   <li>写给自己（receiverId 为 null）：仅本人可见。</li>
 *   <li>写给好友：发送方与接收方均可见。</li>
 *   <li>未到期一律不返回正文（连带密文也不外泄），只返回封存状态与开启日期。</li>
 * </ul>
 */
@Service
public class TimeCapsuleService {

    /** 正文最大长度 */
    private static final int MAX_CONTENT_LENGTH = 2000;
    /** 最多可预约到未来多少年，防止误填 9999 年之类的无效日期 */
    private static final int MAX_YEARS_AHEAD = 50;

    @Autowired
    private TimeCapsuleMapper capsuleMapper;
    @Autowired
    private CapsuleCrypto capsuleCrypto;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 创建胶囊。
     *
     * @param receiverId 收件人；null 表示写给未来的自己
     * @return 新建胶囊 id
     */
    public Long create(Long senderId, Long receiverId, String content, LocalDateTime openTime) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("胶囊内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("内容过长（最多 " + MAX_CONTENT_LENGTH + " 字）");
        }
        if (openTime == null) throw new IllegalArgumentException("请选择开启日期");
        LocalDateTime earliest = LocalDateTime.now().plusMinutes(1);
        if (openTime.isBefore(earliest)) {
            throw new IllegalArgumentException("开启时间必须晚于当前时间");
        }
        if (openTime.isAfter(LocalDateTime.now().plusYears(MAX_YEARS_AHEAD))) {
            throw new IllegalArgumentException("开启时间最多可预约到 " + MAX_YEARS_AHEAD + " 年后");
        }

        TimeCapsule c = new TimeCapsule();
        c.setSenderId(senderId);
        c.setReceiverId(receiverId);
        c.setContentEnc(capsuleCrypto.encrypt(content.trim()));
        c.setOpenTime(openTime);
        c.setStatus("SEALED");
        capsuleMapper.insert(c);
        return c.getId();
    }

    /**
     * 我相关的胶囊列表（我写的 + 别人写给我的），已解锁的带明文正文。
     * 未解锁的只给状态与开启日期，正文与密文都不返回前端。
     */
    public List<Map<String, Object>> listMine(Long uid) {
        List<TimeCapsule> sent = capsuleMapper.findBySender(uid);
        List<TimeCapsule> received = capsuleMapper.findByReceiver(uid);
        List<TimeCapsule> all = new ArrayList<>();
        if (sent != null) all.addAll(sent);
        if (received != null) {
            for (TimeCapsule c : received) {
                // 写给自己的胶囊 senderId == receiverId，避免重复
                if (!uid.equals(c.getSenderId())) all.add(c);
            }
        }
        all.sort((a, b) -> {
            int cmp = a.getOpenTime().compareTo(b.getOpenTime());
            return cmp != 0 ? cmp : b.getId().compareTo(a.getId());
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (TimeCapsule c : all) out.add(toView(c, uid));
        return out;
    }

    /** 查看单个胶囊（未解锁则不含正文） */
    public Map<String, Object> view(Long uid, Long capsuleId) {
        TimeCapsule c = capsuleMapper.selectById(capsuleId);
        if (c == null) throw new IllegalArgumentException("胶囊不存在");
        if (!canView(uid, c)) throw new IllegalArgumentException("无权查看该胶囊");
        return toView(c, uid);
    }

    private boolean canView(Long uid, TimeCapsule c) {
        return uid.equals(c.getSenderId()) || (c.getReceiverId() != null && uid.equals(c.getReceiverId()));
    }

    /** 组装返回给前端的视图：到期已解锁才解密正文 */
    private Map<String, Object> toView(TimeCapsule c, Long uid) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("senderId", c.getSenderId());
        m.put("receiverId", c.getReceiverId());
        m.put("openTime", c.getOpenTime() == null ? null : c.getOpenTime().toString());
        m.put("status", c.getStatus());
        m.put("createTime", c.getCreateTime() == null ? null : c.getCreateTime().toString());
        m.put("unlockedTime", c.getUnlockedTime() == null ? null : c.getUnlockedTime().toString());
        m.put("toSelf", c.getReceiverId() == null);
        m.put("mine", uid.equals(c.getSenderId()));
        boolean unlocked = "UNLOCKED".equals(c.getStatus());
        m.put("unlocked", unlocked);
        if (unlocked) {
            m.put("content", safeDecrypt(c));
        } else {
            m.put("content", null);
        }
        return m;
    }

    /** 解密失败时不让整个列表挂掉，降级为错误提示文本 */
    private String safeDecrypt(TimeCapsule c) {
        try {
            return capsuleCrypto.decrypt(c.getContentEnc());
        } catch (Exception e) {
            return "（胶囊解密失败，可能是密钥已变更）";
        }
    }

    /**
     * 定时任务：扫描到期胶囊 → 置为已解锁 → 发站内通知 + WebSocket 实时推送。
     * 由 InnovationTasks 每分钟调用；返回本次解锁的数量。
     */
    public int unlockDueCapsules() {
        List<TimeCapsule> due = capsuleMapper.findDueForUnlock(LocalDateTime.now());
        if (due == null || due.isEmpty()) return 0;
        int count = 0;
        for (TimeCapsule c : due) {
            capsuleMapper.updateStatus(c.getId(), "UNLOCKED", LocalDateTime.now());
            // 收件人 = 明确指定的人；没指定就是写给未来的自己，通知发送者本人
            Long notifyUid = c.getReceiverId() != null ? c.getReceiverId() : c.getSenderId();
            String title = "时间胶囊已解锁";
            String content = (c.getReceiverId() == null)
                    ? "你写给未来自己的时间胶囊已到开启时间，快来看看当时的自己说了什么吧～"
                    : "有人给你写了一封时间胶囊，现已到开启时间，快来拆开看看～";
            try {
                noticeService.createUserNotice(c.getSenderId(), notifyUid, title, content, c.getId(), "CAPSULE");
            } catch (Exception ignored) {
                // 通知失败不影响解锁本身
            }
            Map<String, Object> evt = new HashMap<>();
            evt.put("type", "CAPSULE_UNLOCKED");
            evt.put("capsuleId", c.getId());
            evt.put("title", title);
            evt.put("content", content);
            messagingTemplate.convertAndSend("/topic/user/" + notifyUid, evt);
            count++;
        }
        return count;
    }

    /** 便捷方法：把 LocalDate（前端只选到日）转成当天 00:00 的时间点 */
    public static LocalDateTime atStartOfDay(LocalDate date) {
        return date.atStartOfDay();
    }
}
