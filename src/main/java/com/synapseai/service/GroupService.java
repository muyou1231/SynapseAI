package com.synapseai.service;

import com.synapseai.entity.ChatGroup;
import com.synapseai.entity.GroupMember;
import com.synapseai.mapper.GroupMemberMapper;
import com.synapseai.mapper.GroupMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GroupService {

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private GroupMemberMapper groupMemberMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /** 建群：当前用户为群主，memberIds 为初始成员 */
    public Map<String, Object> create(Long ownerId, String name, List<Long> memberIds) {
        if (name == null || name.isBlank()) {
            throw new RuntimeException("群名称不能为空");
        }
        ChatGroup g = new ChatGroup();
        g.setName(name);
        g.setOwnerId(ownerId);
        groupMapper.insert(g);

        GroupMember owner = new GroupMember();
        owner.setGroupId(g.getId());
        owner.setUserId(ownerId);
        owner.setRole("OWNER");
        groupMemberMapper.insert(owner);

        List<Long> allMemberIds = new ArrayList<>();
        allMemberIds.add(ownerId);
        if (memberIds != null) {
            for (Long mid : memberIds) {
                if (mid.equals(ownerId)) {
                    continue;
                }
                addMember(g.getId(), mid);
                allMemberIds.add(mid);
            }
        }
        // 通知每位成员（含群主）群已建立，触发对方前端刷新会话列表并订阅群消息
        notifyGroupJoined(g, ownerId, allMemberIds);
        return toGroupVo(g, groupMemberMapper.findByGroupId(g.getId()).size());
    }

    public void invite(Long groupId, List<Long> userIds) {
        if (userIds == null) {
            return;
        }
        ChatGroup g = groupMapper.selectById(groupId);
        for (Long uid : userIds) {
            addMember(groupId, uid);
            // 通知新成员加入群聊，触发对方前端刷新会话列表并订阅群消息
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "GROUP_JOINED");
            payload.put("groupId", groupId);
            payload.put("groupName", g != null ? g.getName() : "群聊");
            payload.put("ownerId", g != null ? g.getOwnerId() : null);
            messagingTemplate.convertAndSend("/topic/user/" + uid, payload);
        }
    }

    /** 向群内每位成员推送「加入群聊」通知（群主也会收到，前端据此刷新会话列表并订阅主题） */
    private void notifyGroupJoined(ChatGroup g, Long ownerId, List<Long> memberIds) {
        for (Long uid : memberIds) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "GROUP_JOINED");
            payload.put("groupId", g.getId());
            payload.put("groupName", g.getName());
            payload.put("ownerId", ownerId);
            messagingTemplate.convertAndSend("/topic/user/" + uid, payload);
        }
    }

    private void addMember(Long groupId, Long userId) {
        if (groupMemberMapper.existsByGroupIdAndUserId(groupId, userId)) {
            return;
        }
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole("MEMBER");
        groupMemberMapper.insert(m);
    }

    /** 移除成员（不能移除群主） */
    public void removeMember(Long groupId, Long userId) {
        GroupMember gm = groupMemberMapper.findByGroupIdAndUserId(groupId, userId);
        if (gm == null) {
            throw new RuntimeException("该用户不在群内");
        }
        if ("OWNER".equals(gm.getRole())) {
            throw new RuntimeException("不能移除群主");
        }
        groupMemberMapper.deleteByGroupIdAndUserId(groupId, userId);
    }

    /** 退出群聊：群主退出则解散群（软删除，保留历史数据） */
    public void quit(Long uid, Long groupId) {
        GroupMember gm = groupMemberMapper.findByGroupIdAndUserId(groupId, uid);
        if (gm == null) {
            return;
        }
        if ("OWNER".equals(gm.getRole())) {
            dissolve(uid, groupId);
        } else {
            groupMemberMapper.deleteByGroupIdAndUserId(groupId, uid);
        }
    }

    /** 群主解散群聊：置 deleted=1（保留历史），移除群主自身成员关系，并通知其余成员 */
    public void dissolve(Long uid, Long groupId) {
        ChatGroup g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new RuntimeException("群聊不存在");
        }
        if (!g.getOwnerId().equals(uid)) {
            throw new RuntimeException("只有群主可以解散群聊");
        }
        g.setDeleted(1);
        groupMapper.updateById(g);
        // 群主自身退出成员关系：不再出现在群主会话列表，但其余成员仍可见历史
        groupMemberMapper.deleteByGroupIdAndUserId(groupId, uid);
        // 通知其余成员：群已解散（成员关系仍保留，仅禁止发消息）
        List<GroupMember> rest = groupMemberMapper.findByGroupId(groupId);
        for (GroupMember m : rest) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "GROUP_DISSOLVED");
            payload.put("groupId", groupId);
            payload.put("groupName", g.getName());
            messagingTemplate.convertAndSend("/topic/user/" + m.getUserId(), payload);
        }
    }

    public ChatGroup getGroup(Long groupId) {
        return groupMapper.selectById(groupId);
    }

    /** 我加入的群列表 */
    public List<Map<String, Object>> myGroups(Long uid) {
        List<GroupMember> members = groupMemberMapper.findByUserId(uid);
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember gm : members) {
            ChatGroup g = groupMapper.selectById(gm.getGroupId());
            if (g != null) {
                result.add(toGroupVo(g, groupMemberMapper.findByGroupId(g.getId()).size()));
            }
        }
        return result;
    }

    /** 群成员列表（含角色） */
    public List<Map<String, Object>> members(Long groupId) {
        List<GroupMember> list = groupMemberMapper.findByGroupId(groupId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember gm : list) {
            Map<String, Object> u = userService.getSafeUser(gm.getUserId());
            if (u == null) {
                continue;
            }
            u.put("role", gm.getRole());
            result.add(u);
        }
        return result;
    }

    public boolean isMember(Long groupId, Long userId) {
        return groupMemberMapper.existsByGroupIdAndUserId(groupId, userId);
    }

    private Map<String, Object> toGroupVo(ChatGroup g, int memberCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("avatar", g.getAvatar());
        m.put("ownerId", g.getOwnerId());
        m.put("memberCount", memberCount);
        m.put("deleted", g.getDeleted() == null ? 0 : g.getDeleted());
        return m;
    }
}
