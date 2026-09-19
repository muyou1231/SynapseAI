package com.synapseai.service;

import com.synapseai.entity.Moment;
import com.synapseai.entity.MomentLike;
import com.synapseai.entity.User;
import com.synapseai.mapper.MomentLikeMapper;
import com.synapseai.mapper.MomentMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 朋友圈点赞业务。
 * 点赞 = 插入记录；取消点赞 = 删除记录（唯一键防重复）。
 * toggle 返回 { liked, likeCount }，供前端直接刷新按钮状态与计数。
 */
@Service
public class MomentLikeService {

    @Autowired
    private MomentLikeMapper likeMapper;
    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NoticeService noticeService;

    /** 切换点赞状态：已赞则取消，未赞则点赞。返回 { liked(切换后的状态), likeCount } */
    public Map<String, Object> toggle(Long uid, Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) {
            throw new RuntimeException("动态不存在");
        }
        int before = likeMapper.countByUser(momentId, uid);
        boolean willLike = before == 0;
        if (willLike) {
            MomentLike l = new MomentLike();
            l.setMomentId(momentId);
            l.setUserId(uid);
            likeMapper.insert(l);
            // 点赞后通知动态作者（非本人点赞时）：持久化通知（标红），不弹窗
            if (!m.getUserId().equals(uid)) {
                User fu = userMapper.selectById(uid);
                String fromName = fu != null ? fu.getNickname() : ("用户" + uid);
                noticeService.createUserNotice(uid, m.getUserId(), "朋友圈新点赞",
                        fromName + " 赞了你的动态", momentId, "LIKE");
            }
        } else {
            likeMapper.deleteByUser(momentId, uid);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("liked", willLike);
        res.put("likeCount", likeMapper.countByMoment(momentId));
        return res;
    }

    /** 某条动态点赞数 */
    public int count(Long momentId) {
        return likeMapper.countByMoment(momentId);
    }

    /** 某用户是否点赞了某动态 */
    public boolean liked(Long uid, Long momentId) {
        return likeMapper.countByUser(momentId, uid) > 0;
    }

    /** 批量：返回某用户在给定动态集合中已点赞的动态 id 集合（feed 用，避免 N+1） */
    public Set<Long> likedSet(Long uid, List<Long> momentIds) {
        if (momentIds == null || momentIds.isEmpty()) return new HashSet<>();
        return new HashSet<>(likeMapper.likedMomentIds(uid, momentIds));
    }

    /** 级联删除：动态被删时清理其全部点赞 */
    public void deleteByMoment(Long momentId) {
        likeMapper.deleteByMoment(momentId);
    }
}
