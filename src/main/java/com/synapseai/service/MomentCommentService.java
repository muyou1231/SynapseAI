package com.synapseai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapseai.entity.Moment;
import com.synapseai.entity.MomentComment;
import com.synapseai.entity.User;
import com.synapseai.mapper.MomentCommentMapper;
import com.synapseai.mapper.MomentMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 朋友圈评论（支持文字 + 图片）。
 */
@Service
public class MomentCommentService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MomentCommentMapper commentMapper;
    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NoticeService noticeService;


    /** 发表评论（content 与 images 至少其一非空；parentId 非 null 表示回复某条评论）。
     *  评论不再弹窗提示，而是为「动态作者」与「被回复者」各生成一条持久化通知（category=COMMENT），
     *  由前端在「多多的家园」标签上标红；评论后失效广场缓存，保证其他用户点击多多的家园即见最新评论数。 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public Map<String, Object> comment(Long uid, Long momentId, String content, List<String> images, Long parentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        boolean hasText = content != null && !content.trim().isEmpty();
        boolean hasImg = images != null && !images.isEmpty();
        if (!hasText && !hasImg) {
            throw new RuntimeException("评论内容不能为空");
        }
        MomentComment c = new MomentComment();
        c.setMomentId(momentId);
        c.setUserId(uid);
        c.setParentId(parentId);
        c.setContent(hasText ? content.trim() : null);
        try {
            c.setImages(JSON.writeValueAsString(hasImg ? images : new ArrayList<String>()));
        } catch (Exception e) {
            c.setImages("[]");
        }
        commentMapper.insert(c);
        // 评论人昵称（用于通知文案）
        User fu = userMapper.selectById(uid);
        String fromName = fu != null ? fu.getNickname() : ("用户" + uid);
        String snippet = buildSnippet(content, images);
        // 评论后通知动态作者（非本人时）：持久化通知（标红），不弹窗
        if (!m.getUserId().equals(uid)) {
            noticeService.createUserNotice(uid, m.getUserId(), "朋友圈新评论",
                    fromName + " 评论了你的动态：" + snippet, momentId);
        }
        // 回复他人评论时，额外通知被回复者（非本人、且非动态作者，避免重复通知）
        if (parentId != null) {
            MomentComment parent = commentMapper.selectById(parentId);
            if (parent != null && !parent.getUserId().equals(uid) && !parent.getUserId().equals(m.getUserId())) {
                noticeService.createUserNotice(uid, parent.getUserId(), "朋友圈新回复",
                        fromName + " 回复了你的评论：" + snippet, momentId);
            }
        }
        Map<String, Object> result = enrichOne(c);
        // 回复他人评论时，返回数据带上被回复者昵称与 id，前端无需刷新即可显示「谁回复了谁」
        if (parentId != null) {
            MomentComment parent = commentMapper.selectById(parentId);
            if (parent != null) {
                User pu = userMapper.selectById(parent.getUserId());
                if (pu != null) {
                    result.put("replyToName", pu.getNickname());
                    result.put("replyToId", pu.getId());
                }
            }
        }
        return result;
    }

    /** 取通知文案片段：文字取前 30 字，仅有图片则显示[图片] */
    private String buildSnippet(String content, List<String> images) {
        if (content != null && !content.trim().isEmpty()) {
            String s = content.trim();
            return s.length() > 30 ? s.substring(0, 30) + "…" : s;
        }
        return (images != null && !images.isEmpty()) ? "[图片]" : "";
    }

    /** 某条动态下的全部评论（按时间正序） */
    public List<Map<String, Object>> listByMoment(Long momentId) {
        return enrich(commentMapper.selectByMoment(momentId));
    }

    /** 删除评论：仅评论作者或动态作者可删（同时失效广场缓存，刷新评论数） */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void delete(Long uid, Long commentId) {
        MomentComment c = commentMapper.selectById(commentId);
        if (c == null) throw new RuntimeException("评论不存在");
        Moment m = momentMapper.selectById(c.getMomentId());
        boolean isCommentAuthor = c.getUserId().equals(uid);
        boolean isMomentAuthor = m != null && m.getUserId().equals(uid);
        if (!isCommentAuthor && !isMomentAuthor) {
            throw new RuntimeException("只能删除自己发布的评论");
        }
        commentMapper.deleteById(commentId);
    }

    /* ---------------- 内部工具 ---------------- */

    private List<Map<String, Object>> enrich(List<MomentComment> list) {
        Map<Long, MomentComment> byId = new HashMap<>();
        for (MomentComment c : list) byId.put(c.getId(), c);
        List<Map<String, Object>> res = new ArrayList<>();
        for (MomentComment c : list) {
            Map<String, Object> m = enrichOne(c);
            Long pid = c.getParentId();
            if (pid != null && byId.containsKey(pid)) {
                User pu = userMapper.selectById(byId.get(pid).getUserId());
                if (pu != null) {
                    m.put("replyToName", pu.getNickname());
                    m.put("replyToId", pu.getId());
                }
            }
            res.add(m);
        }
        return res;
    }

    private Map<String, Object> enrichOne(MomentComment c) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", c.getId());
        map.put("momentId", c.getMomentId());
        map.put("parentId", c.getParentId());
        map.put("content", c.getContent());
        map.put("createTime", c.getCreateTime() != null ? c.getCreateTime().format(FMT) : null);
        List<String> imgs = new ArrayList<>();
        try {
            if (c.getImages() != null && !c.getImages().isEmpty()) {
                imgs = JSON.readValue(c.getImages(), List.class);
            }
        } catch (Exception ignored) { }
        map.put("images", imgs);
        User u = userMapper.selectById(c.getUserId());
        Map<String, Object> author = new HashMap<>();
        if (u != null) {
            author.put("id", u.getId());
            author.put("nickname", u.getNickname());
            author.put("username", u.getUsername());
            author.put("avatar", u.getAvatar());
            author.put("account", u.getAccount());
        }
        map.put("author", author);
        return map;
    }
}
