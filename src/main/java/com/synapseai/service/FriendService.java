package com.synapseai.service;

import com.synapseai.entity.Friendship;
import com.synapseai.entity.User;
import com.synapseai.mapper.FriendshipMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FriendService {

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private AiAssistantService aiAssistantService;

    @Autowired
    private UserMapper userMapper;

    /** 发起好友申请 */
    public void apply(Long uid, Long toId) {
        if (uid.equals(toId)) {
            throw new RuntimeException("不能添加自己为好友");
        }
        // 目标为 AI 助手账户：直接建立双向 ACCEPTED 好友，不走申请-同意流程
        if (aiAssistantService.isAssistant(toId)) {
            aiAssistantService.addFriendDirect(uid, toId);
            return;
        }
        if (friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, toId, "ACCEPTED")) {
            throw new RuntimeException("你们已经是好友了");
        }
        if (!friendshipMapper.existsByUserIdAndFriendId(uid, toId)) {
            Friendship f = new Friendship();
            f.setUserId(uid);
            f.setFriendId(toId);
            f.setStatus("PENDING");
            friendshipMapper.insert(f);
            // 实时通知接收方收到好友申请
            Map<String, Object> notice = new HashMap<>();
            notice.put("type", "FRIEND_REQUEST");
            notice.put("from", uid);
            notice.put("fromUser", userService.getSafeUser(uid));
            messagingTemplate.convertAndSend("/topic/user/" + toId, notice);
        }
    }

    /** 同意好友申请 */
    @Caching(evict = {
            @CacheEvict(value = "friendship", key = "#uid + ':' + #fromId"),
            @CacheEvict(value = "friendship", key = "#fromId + ':' + #uid"),
            @CacheEvict(value = "momentFeed", allEntries = true)
    })
    public void accept(Long uid, Long fromId) {
        Friendship f = friendshipMapper.findByUserIdAndFriendId(fromId, uid);
        if (f == null) {
            throw new RuntimeException("没有待处理的好友申请");
        }
        if (!"PENDING".equals(f.getStatus())) {
            throw new RuntimeException("申请状态不正确");
        }
        f.setStatus("ACCEPTED");
        friendshipMapper.updateById(f);
        if (!friendshipMapper.existsByUserIdAndFriendId(uid, fromId)) {
            Friendship reverse = new Friendship();
            reverse.setUserId(uid);
            reverse.setFriendId(fromId);
            reverse.setStatus("ACCEPTED");
            friendshipMapper.insert(reverse);
        }
        // 通知申请人：对方已通过你的好友申请
        Map<String, Object> notice = new HashMap<>();
        notice.put("type", "FRIEND_ACCEPTED");
        notice.put("from", uid);
        notice.put("user", userService.getSafeUser(uid));
        messagingTemplate.convertAndSend("/topic/user/" + fromId, notice);
    }

    /** 拉黑（单向）：仅记录「我(uid)拉黑对方(friendId)」，不改动对方视角那一行。
     *  被拉黑的一方(friendId)无法给 uid 发消息，但在自己的通讯录里看不到被拉黑、也没有「取消拉黑」入口。 */
    @Caching(evict = {
            @CacheEvict(value = "blocked", key = "#friendId + ':' + #uid"),
            @CacheEvict(value = "momentFeed", allEntries = true)
    })
    public void block(Long uid, Long friendId) {
        if (uid.equals(friendId)) {
            throw new RuntimeException("不能拉黑自己");
        }
        Friendship f = friendshipMapper.findByUserIdAndFriendId(uid, friendId);
        if (f == null) {
            f = new Friendship();
            f.setUserId(uid);
            f.setFriendId(friendId);
            f.setStatus("BLOCKED");
            friendshipMapper.insert(f);
        } else if (!"BLOCKED".equals(f.getStatus())) {
            f.setStatus("BLOCKED");
            friendshipMapper.updateById(f);
        }
    }

    /** 解除拉黑（仅我自己拉黑的才能解除）：只把「我(uid)→对方(friendId)」这行恢复为好友。
     *  不能解除「对方拉黑我」——只有拉黑发起方本人才能取消拉黑。 */
    @Caching(evict = {
            @CacheEvict(value = "blocked", key = "#friendId + ':' + #uid"),
            @CacheEvict(value = "momentFeed", allEntries = true)
    })
    public void unblock(Long uid, Long friendId) {
        Friendship f = friendshipMapper.findByUserIdAndFriendId(uid, friendId);
        if (f != null && "BLOCKED".equals(f.getStatus())) {
            f.setStatus("ACCEPTED");
            friendshipMapper.updateById(f);
        }
    }

    /** 发送方(sender)能否给接收方(target)发单聊消息：仅当 target 把 sender 拉黑时禁止（单向）。
     *  即「对方已把你拉黑」时 sender→target 失败，而 blocker→被拉黑方 仍可正常发送。 */
    @Cacheable(value = "blocked", key = "#sender + ':' + #target")
    public boolean isBlocked(Long sender, Long target) {
        return friendshipMapper.existsByUserIdAndFriendIdAndStatus(target, sender, "BLOCKED");
    }

    /** 拒绝好友申请 */
    public void reject(Long uid, Long fromId) {
        Friendship f = friendshipMapper.findByUserIdAndFriendId(fromId, uid);
        if (f != null) {
            friendshipMapper.deleteById(f.getId());
        }
    }

    /** 好友列表（含被自己拉黑的，blocked=true）。拉黑的仍保留在列表里，可取消拉黑。
     *  每个好友附带 remark（备注名，可为 null）。 */
    public List<Map<String, Object>> list(Long uid) {
        List<Friendship> fs = friendshipMapper.findByUserIdAndStatusIn(
                uid, List.of("ACCEPTED", "BLOCKED"));
        return fs.stream().map(f -> {
            Long friendId = f.getFriendId();
            Map<String, Object> m = userService.getSafeUser(friendId);
            if (m != null) {
                m.put("blocked", "BLOCKED".equals(f.getStatus()));
                m.put("remark", f.getRemark());
            }
            return m;
        }).filter(Objects::nonNull).toList();
    }

    /** 我收到的待处理申请 */
    public List<Map<String, Object>> requests(Long uid) {
        List<Friendship> fs = friendshipMapper.findByFriendIdAndStatus(uid, "PENDING");
        return fs.stream()
                .map(f -> userService.getSafeUser(f.getUserId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /** 删除好友（双向） */
    @Caching(evict = {
            @CacheEvict(value = "friendship", key = "#uid + ':' + #friendId"),
            @CacheEvict(value = "friendship", key = "#friendId + ':' + #uid"),
            @CacheEvict(value = "blocked", key = "#uid + ':' + #friendId"),
            @CacheEvict(value = "blocked", key = "#friendId + ':' + #uid"),
            @CacheEvict(value = "momentFeed", allEntries = true)
    })
    public void delete(Long uid, Long friendId) {
        Friendship f1 = friendshipMapper.findByUserIdAndFriendId(uid, friendId);
        if (f1 != null) {
            friendshipMapper.deleteById(f1.getId());
        }
        Friendship f2 = friendshipMapper.findByUserIdAndFriendId(friendId, uid);
        if (f2 != null) {
            friendshipMapper.deleteById(f2.getId());
        }
    }

    @Cacheable(value = "friendship", key = "#uid + ':' + #other")
    public boolean isFriend(Long uid, Long other) {
        return friendshipMapper.existsByUserIdAndFriendIdAndStatus(uid, other, "ACCEPTED");
    }

    /** 设置好友备注（仅「我(uid)→对方(friendId)」这一行），备注名仅自己可见。
     *  传空串 / null 表示清除备注。 */
    public void setRemark(Long uid, Long friendId, String remark) {
        Friendship f = friendshipMapper.findByUserIdAndFriendId(uid, friendId);
        if (f == null || !"ACCEPTED".equals(f.getStatus())) {
            throw new RuntimeException("你们还不是好友");
        }
        if (remark != null) remark = remark.trim();
        if (remark != null && remark.length() > 64) {
            throw new RuntimeException("备注长度不能超过 64 个字符");
        }
        friendshipMapper.updateRemark(uid, friendId, remark);
    }

    /** 在「我添加的用户」中按账号或用户名搜索（不搜陌生人、不搜待处理申请）。
     *  返回结果含 user safe 信息 + 该好友的 remark + blocked 标志。 */
    public List<Map<String, Object>> searchMyFriends(Long uid, String kw) {
        if (kw == null || kw.isBlank()) return List.of();
        List<Long> ids = friendshipMapper.searchMyFriendIds(uid, kw.trim(), 50);
        return ids.stream().map(fid -> {
            Map<String, Object> m = userService.getSafeUser(fid);
            if (m != null) {
                Friendship f = friendshipMapper.findByUserIdAndFriendId(uid, fid);
                m.put("remark", f != null ? f.getRemark() : null);
                m.put("blocked", f != null && "BLOCKED".equals(f.getStatus()));
            }
            return m;
        }).filter(Objects::nonNull).toList();
    }
}
