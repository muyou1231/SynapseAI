package com.synapseai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapseai.entity.Moment;
import com.synapseai.entity.MomentSetting;
import com.synapseai.entity.User;
import com.synapseai.mapper.FriendshipMapper;
import com.synapseai.mapper.MomentMapper;
import com.synapseai.mapper.MomentSettingMapper;
import com.synapseai.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 朋友圈（动态）业务。
 * 权限分两层：
 *  1) 全局「查看范围」(moment_setting.visibility，存 scope) —— 对作者所有动态统一生效：
 *     INVISIBLE=家园谁也不可见 / THREE_DAY=仅三天可见 / ONE_MONTH=一个月可见 / HALF_YEAR=半年内可见
 *  2) 逐条「查看权限」(moment.allow_list / deny_list) —— 仅对单条动态生效：
 *     denyList=不给谁看(优先级最高)，allowList=部分可见(白名单)
 * 无自定义时长；全局默认 HALF_YEAR。
 */
@Service
public class MomentService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> SCOPE = Arrays.asList("INVISIBLE", "THREE_DAY", "ONE_MONTH", "HALF_YEAR");
    /** 逐条可见范围（关系范围）：公开 / 仅好友 / 私密 / 部分好友 */
    private static final List<String> REL = Arrays.asList("PUBLIC", "FRIENDS", "PRIVATE", "PARTIAL");

    @Autowired
    private MomentMapper momentMapper;
    @Autowired
    private MomentSettingMapper settingMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FriendshipMapper friendMapper;
    @Autowired
    private MomentCommentService commentService;
    @Autowired
    private ReviewConfigService reviewConfigService;
    @Autowired
    private AiAssistantService aiAssistantService;
    @Autowired
    private MomentLikeService likeService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /** AI 审核异步任务的共享线程池：复用线程而非每发一条动态都 new Thread，与 ChatController/AiController 同思路 */
    private final ExecutorService aiReviewExecutor = Executors.newCachedThreadPool();

    /* ================ 全局查看范围设置 ================ */

    /** 读取用户的全局朋友圈查看范围；不存在则返回默认(HALF_YEAR)。旧值兼容映射为合法 scope。 */
    public MomentSetting getSetting(Long uid) {
        MomentSetting s = settingMapper.findByUser(uid);
        if (s == null) {
            s = new MomentSetting();
            s.setUserId(uid);
            s.setVisibility("HALF_YEAR");
        } else {
            s.setVisibility(normalizeScope(s.getVisibility()));
        }
        return s;
    }

    /** 保存用户的全局查看范围（upsert），仅 scope 生效 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public MomentSetting saveSetting(Long uid, String scope) {
        MomentSetting s = new MomentSetting();
        s.setUserId(uid);
        s.setVisibility(normalizeScope(scope));
        settingMapper.upsert(s);
        return getSetting(uid);
    }

    /** 返回全局设置（前端可直接用） */
    public Map<String, Object> getSettingView(Long uid) {
        MomentSetting s = getSetting(uid);
        Map<String, Object> map = new HashMap<>();
        map.put("visibility", s.getVisibility());
        return map;
    }

    /* ================ 动态发布/查询 ================ */

    /** 发布一条动态；content 与 images 至少其一非空。
     *  逐条可见范围(visibility)由前端选择，默认仅好友(FRIENDS)；全局时间窗口由家园设置控制。 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public Moment publish(Long uid, String content, List<String> images,
                          String visibility, List<Long> allowList, List<Long> denyList) {
        boolean hasText = content != null && !content.trim().isEmpty();
        boolean hasImg = images != null && !images.isEmpty();
        if (!hasText && !hasImg) {
            throw new RuntimeException("不能发布空内容");
        }
        Moment m = new Moment();
        m.setUserId(uid);
        m.setContent(hasText ? content.trim() : null);
        try {
            m.setImages(JSON.writeValueAsString(hasImg ? images : new ArrayList<String>()));
        } catch (Exception e) {
            m.setImages("[]");
        }
        // 逐条可见范围（默认仅好友）；allow/deny 为附加权限
        m.setVisibility(normalizeVisibility(visibility));
        m.setAllowList(toJson(allowList));
        m.setDenyList(toJson(denyList));
        m.setExpireTime(null);
        // 审核模式决定发布态：
        //  AUTO=直接发布(NORMAL)；MANUAL=进入 PENDING 待人工；AI=先用 AI 判定，通过则 NORMAL，不通过则 PENDING 待人工复审
        String mode = reviewConfigService.getMode();
        if ("AUTO".equalsIgnoreCase(mode)) {
            m.setStatus("NORMAL");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
        } else if ("AI".equalsIgnoreCase(mode)) {
            // 异步审核：先落库为「AI 审核中」，立即返回不阻塞用户；后台线程调 AI，判完回写结论。
            m.setStatus("AI_REVIEWING");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
            momentMapper.insert(m);
            final String reviewText = hasText ? content.trim() : "";
            final Long newId = m.getId();
            final Long authorId = uid;
            aiReviewExecutor.execute(() -> asyncAiReview(newId, authorId, reviewText));
            return m;
        } else {
            // MANUAL 人工审核
            m.setStatus("PENDING");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
        }
        momentMapper.insert(m);
        return m;
    }

    /**
     * 后台线程执行 AI 审核：调 moyo 助手判定，回写 ai_review/ai_suggestion/status，
     * 并通过 WebSocket 通知作者审核结果（/topic/user/{authorId}）。
     * 失败/异常时降级为「待人工复审」(PENDING)，避免内容卡在审核中。
     */
    private void asyncAiReview(Long momentId, Long authorId, String reviewText) {
        try {
            Map<String, Object> ai = aiAssistantService.reviewMoment(reviewText);
            boolean pass = Boolean.TRUE.equals(ai.get("pass"));
            String suggestion = (String) ai.get("suggestion");
            String status = pass ? "NORMAL" : "PENDING";
            momentMapper.updateAiReview(momentId, pass ? "PASS" : "FAIL", suggestion, status);
            // 通知作者审核完成
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("type", "MOMENT_AI_REVIEW_DONE");
            note.put("momentId", momentId);
            note.put("pass", pass);
            note.put("status", status);
            note.put("aiReview", pass ? "PASS" : "FAIL");
            note.put("aiSuggestion", suggestion == null ? "" : suggestion);
            messagingTemplate.convertAndSend("/topic/user/" + authorId, note);
        } catch (Exception e) {
            // 异常降级：转人工复审，避免卡死在 AI_REVIEWING
            momentMapper.updateAiReview(momentId, "FAIL", "AI 审核异常，已转人工复审", "PENDING");
            try {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", "MOMENT_AI_REVIEW_DONE");
                note.put("momentId", momentId);
                note.put("pass", false);
                note.put("status", "PENDING");
                note.put("aiReview", "FAIL");
                note.put("aiSuggestion", "AI 审核异常，已转人工复审");
                messagingTemplate.convertAndSend("/topic/user/" + authorId, note);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 家园动态流：仅展示「当前用户好友」的 NORMAL 动态（家园=好友圈，非好友不可见），按时间倒序。
     * 仍受作者隐私出口约束：全局范围 INVISIBLE（谁都不可见）/ 时间窗口过期、逐条 PRIVATE（仅作者）、
     * PARTIAL（仅白名单）、denyList（不给谁看）均不显示。PUBLIC/FRIENDS 仅对好友可见。
     */
    @Cacheable(value = "momentFeed", key = "#uid + ':' + #limit")
    public List<Map<String, Object>> feed(Long uid, int limit) {
        List<Moment> list = momentMapper.selectPublicFeed(limit);
        Map<Long, MomentSetting> settingCache = new HashMap<>();
        Map<Long, Boolean> friendCache = new HashMap<>();   // 作者 -> viewer 是否其好友（避免 N+1）
        List<Map<String, Object>> res = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Moment m : list) {
            // 仅「正常(NORMAL)」动态进入广场；待审核(PENDING)与已打回(REJECTED)均不公开
            if (m.getStatus() != null && !"NORMAL".equals(m.getStatus())) continue;
            MomentSetting s = settingCache.get(m.getUserId());
            if (s == null) {
                s = getSetting(m.getUserId());
                settingCache.put(m.getUserId(), s);
            }
            // 家园为好友圈：基于真实双向好友关系判定可见性。非好友看不到对方的动态
            // （含 PUBLIC/FRIENDS），仅好友可见 PUBLIC/FRIENDS；白名单好友可见 PARTIAL；PRIVATE 仅作者。
            Boolean fr = friendCache.get(m.getUserId());
            if (fr == null) {
                fr = friendMapper.existsByUserIdAndFriendIdAndStatus(uid, m.getUserId(), "ACCEPTED")
                        || friendMapper.existsByUserIdAndFriendIdAndStatus(m.getUserId(), uid, "ACCEPTED");
                friendCache.put(m.getUserId(), fr);
            }
            if (visibleTo(m, s, uid, fr)) {
                Map<String, Object> item = enrichOne(m, s, uid);
                res.add(item);
                ids.add(m.getId());
            }
        }
        // 批量判定当前用户对这些动态是否已点赞（避免 N+1）
        Set<Long> liked = likeService.likedSet(uid, ids);
        for (Map<String, Object> item : res) {
            item.put("liked", liked.contains(item.get("id")));
        }
        return res;
    }

    /** 我的动态（作者可见自己全部动态，不受权限约束） */
    public List<Map<String, Object>> myMoments(Long uid, int limit) {
        MomentSetting s = getSetting(uid);
        List<Map<String, Object>> res = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Moment m : momentMapper.selectByUser(uid, limit)) {
            Map<String, Object> item = enrichOne(m, s, uid);
            res.add(item);
            ids.add(m.getId());
        }
        Set<Long> liked = likeService.likedSet(uid, ids);
        for (Map<String, Object> item : res) {
            item.put("liked", liked.contains(item.get("id")));
        }
        return res;
    }

    /**
     * 查看某用户（好友）的家园动态：按浏览者视角过滤可见范围。
     * - 看自己 → 等同「我的动态」（含全部状态）。
     * - 看他人 → 仅返回 NORMAL 且对 viewer 可见的动态，复用 visibleTo + enrichOne。
     *   isFriend 取真实好友关系（双向 ACCEPTED）；非好友看不到任何动态（PUBLIC/FRIENDS 均需好友），
     *   白名单好友可见 PARTIAL，PRIVATE 仅作者。
     */
    public List<Map<String, Object>> userMoments(Long viewer, Long target, int limit) {
        if (target.equals(viewer)) return myMoments(viewer, limit);
        boolean isFriend = friendMapper.existsByUserIdAndFriendIdAndStatus(viewer, target, "ACCEPTED")
                || friendMapper.existsByUserIdAndFriendIdAndStatus(target, viewer, "ACCEPTED");
        MomentSetting s = getSetting(target);
        List<Map<String, Object>> res = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Moment m : momentMapper.selectByUser(target, limit)) {
            if (m.getStatus() != null && !"NORMAL".equals(m.getStatus())) continue;
            if (visibleTo(m, s, viewer, isFriend)) {
                Map<String, Object> item = enrichOne(m, s, viewer);
                res.add(item);
                ids.add(m.getId());
            }
        }
        Set<Long> liked = likeService.likedSet(viewer, ids);
        for (Map<String, Object> item : res) {
            item.put("liked", liked.contains(item.get("id")));
        }
        return res;
    }

    /** 删除（仅作者本人） */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void delete(Long uid, Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        if (!m.getUserId().equals(uid)) throw new RuntimeException("只能删除自己的动态");
        momentMapper.deleteById(momentId);
        likeService.deleteByMoment(momentId); // 级联清理点赞
    }

    /** 某条动态下的全部评论（管理端查看用，按时间正序、已富化） */
    public List<Map<String, Object>> getComments(Long momentId) {
        return commentService.listByMoment(momentId);
    }

    /** 管理员打回动态：置状态为 REJECTED，记录打回原因；广场信息流将不再展示该动态（作者仍可在「我的」中看到打回原因，不再单独弹窗通知）。
     *  - 若该条此前用户已申请人工复审（manual_review=1）：保留 manual_review=1，使其进入「打回列表」(仅人工打回) 而非普通队列；
     *    同时清空 ai_review，避免仍残留在全部/AI 审核列表。
     *  - 若属 AI 审核列表（ai_review 非空且未申请人工复审）：打回后清空 AI 审核结论，使其从「全部 AI 审核内容 / AI 打回」列表移除。 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void reject(Long momentId, String reason) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        boolean manualApplied = m.getManualReview() != null && m.getManualReview();
        m.setStatus("REJECTED");
        m.setRejectReason(reason == null ? "" : reason.trim());
        momentMapper.updateStatus(momentId, m.getStatus(), m.getRejectReason());
        // 实时通知作者：人工审核完成，已打回（显示打回结果）
        pushManualReviewUpdate(momentId, "REJECTED", "REJECTED", reason);
        // 从 AI 审核列表中移除（清空 ai_review / ai_suggestion），避免重复出现
        if (m.getAiReview() != null) {
            momentMapper.clearAiReview(momentId);
        }
        // 人工打回：保留 manual_review=1（SQL 中 manual_review 不在 updateStatus 覆盖列，已保留），
        // 仅确保该条进入「打回列表」。清 ai_review 已在上一步完成。
        if (!manualApplied && m.getAiReview() == null) {
            // 纯人工审核模式下（MANUAL 模式）的打回：保持 manual_review 原值（0），不进打回列表 tab。
        }
    }

    /** 管理员撤回打回：恢复为正常状态，清空打回原因 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void restore(Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        m.setStatus("NORMAL");
        m.setRejectReason(null);
        momentMapper.updateStatus(momentId, m.getStatus(), m.getRejectReason());
        // 实时通知作者：人工审核完成，已通过（恢复正常可见）
        pushManualReviewUpdate(momentId, "APPROVED", "NORMAL", null);
    }

    /** 管理员审核通过：待审核/被打回的动态转为正常(NORMAL)并公开，清空打回原因 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void approve(Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        m.setStatus("NORMAL");
        m.setRejectReason(null);
        momentMapper.updateStatus(momentId, m.getStatus(), m.getRejectReason());
        // 实时通知作者：人工审核完成，已通过（恢复正常可见）
        pushManualReviewUpdate(momentId, "APPROVED", "NORMAL", null);
    }

    /** 用户申请人工复审：仅作者本人。
     *  允许入口：① PENDING 且 AI 审核不通过（aiReview=FAIL）；② REJECTED（被管理员打回）。
     *  置 manual_review=1 且 status=MANUAL_REVIEWING，进入「人工审核中」；已处于人工审核中则拒绝（防重复提交）。 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public void applyManualReview(Long uid, Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        if (!m.getUserId().equals(uid)) throw new RuntimeException("只能对自己的动态申请复审");
        if ("MANUAL_REVIEWING".equals(m.getStatus())) throw new RuntimeException("已申请人工复审，正在审核中，请勿重复提交");
        boolean canApply = "PENDING".equals(m.getStatus()) && "FAIL".equals(m.getAiReview())
                || "REJECTED".equals(m.getStatus());
        if (!canApply) throw new RuntimeException("当前动态无需人工复审");
        int rows = momentMapper.applyManualReview(momentId, uid);
        if (rows == 0) throw new RuntimeException("当前动态状态已变化，无法申请人工复审");
        // 实时通知作者：已申请人工复审（状态进入「人工审核中」）
        pushManualReviewUpdate(momentId, "APPLIED", "MANUAL_REVIEWING", null);
    }

    /** 向动态作者实时推送人工复审链路的状态变化（申请/打回/通过），使作者端无需刷新即可看到最新状态 */
    private void pushManualReviewUpdate(Long momentId, String action, String status, String rejectReason) {
        try {
            Moment m = momentMapper.selectById(momentId);
            if (m == null) return;
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("type", "MOMENT_MANUAL_REVIEW");
            note.put("momentId", momentId);
            note.put("action", action);
            note.put("status", status);
            note.put("rejectReason", rejectReason == null ? "" : rejectReason);
            messagingTemplate.convertAndSend("/topic/user/" + m.getUserId(), note);
        } catch (Exception ignored) {}
    }

    /** 用户重新发布（被打回后修改再发）：仅作者本人，更新内容/图片/可见范围，重置审核状态并清空打回原因。
     *  审核模式为 MANUAL 时进入 PENDING 待复审；AUTO 时直接发布(NORMAL)。 */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public Moment update(Long uid, Long momentId, String content, List<String> images,
                         String visibility, List<Long> allowList, List<Long> denyList) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        if (!m.getUserId().equals(uid)) throw new RuntimeException("只能修改自己的动态");
        boolean hasText = content != null && !content.trim().isEmpty();
        boolean hasImg = images != null && !images.isEmpty();
        if (!hasText && !hasImg) throw new RuntimeException("不能发布空内容");
        m.setContent(hasText ? content.trim() : null);
        try {
            m.setImages(JSON.writeValueAsString(hasImg ? images : new ArrayList<String>()));
        } catch (Exception e) {
            m.setImages("[]");
        }
        m.setVisibility(normalizeVisibility(visibility));
        m.setAllowList(toJson(allowList));
        m.setDenyList(toJson(denyList));
        m.setRejectReason(null); // 清除历史打回原因
        // 审核模式：AUTO=直接发布；AI=AI 判定后再决定；MANUAL=进 PENDING
        String mode = reviewConfigService.getMode();
        if ("AUTO".equalsIgnoreCase(mode)) {
            m.setStatus("NORMAL");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
        } else if ("AI".equalsIgnoreCase(mode)) {
            // 异步审核：先置「AI 审核中」并落库，立即返回；后台线程调 AI，判完回写结论。
            m.setStatus("AI_REVIEWING");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
            momentMapper.updateContent(m.getId(), m.getUserId(), m.getContent(), m.getImages(),
                    m.getVisibility(), m.getAllowList(), m.getDenyList(), m.getStatus(), m.getRejectReason());
            final String reviewText = hasText ? content.trim() : "";
            final Long newId = m.getId();
            final Long authorId = m.getUserId();
            aiReviewExecutor.execute(() -> asyncAiReview(newId, authorId, reviewText));
        } else {
            m.setStatus("PENDING");
            m.setAiReview(null);
            m.setAiSuggestion(null);
            m.setManualReview(false);
            momentMapper.updateContent(m.getId(), m.getUserId(), m.getContent(), m.getImages(),
                    m.getVisibility(), m.getAllowList(), m.getDenyList(), m.getStatus(), m.getRejectReason());
        }
        return m;
    }

    /** 修改单条动态的可见范围（仅作者本人；仅改 visibility/allow_list/deny_list 三列） */
    @CacheEvict(value = "momentFeed", allEntries = true)
    public Moment updatePermission(Long uid, Long momentId, String visibility,
                                   List<Long> allowList, List<Long> denyList) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        if (!m.getUserId().equals(uid)) throw new RuntimeException("只能修改自己动态的权限");
        m.setVisibility(normalizeVisibility(visibility));
        m.setAllowList(toJson(allowList));
        m.setDenyList(toJson(denyList));
        momentMapper.updatePermission(m.getId(), m.getVisibility(), m.getAllowList(), m.getDenyList());
        return m;
    }

    /** 按 id 返回富化后的单条（与 feed 结构一致），用于发布后回显 */
    public Map<String, Object> getEnriched(Long id) {
        Moment m = momentMapper.selectById(id);
        if (m == null) return null;
        Map<String, Object> item = enrichOne(m, getSetting(m.getUserId()), m.getUserId());
        item.put("liked", likeService.liked(m.getUserId(), id));
        return item;
    }

    /** 单条动态详情（供通知跳转）：仅当用户可查看时返回富化数据，否则抛异常。
     *  基于真实好友关系隔离：FRIENDS/PUBLIC 仅好友可见，PARTIAL 仅白名单好友，PRIVATE 仅作者，
     *  仍落实 denyList / 全局时间窗口 等约束。 */
    public Map<String, Object> getViewable(Long viewer, Long momentId) {
        Moment m = momentMapper.selectById(momentId);
        if (m == null) throw new RuntimeException("动态不存在");
        // 待审核/已打回的动态仅作者本人可查看详情，他人不可直接打开
        if (m.getStatus() != null && !"NORMAL".equals(m.getStatus()) && !m.getUserId().equals(viewer)) {
            throw new RuntimeException("无权查看该动态");
        }
        MomentSetting s = getSetting(m.getUserId());
        boolean isFriend = friendMapper.existsByUserIdAndFriendIdAndStatus(viewer, m.getUserId(), "ACCEPTED")
                || friendMapper.existsByUserIdAndFriendIdAndStatus(m.getUserId(), viewer, "ACCEPTED");
        if (!visibleTo(m, s, viewer, isFriend)) throw new RuntimeException("无权查看该动态");
        Map<String, Object> item = enrichOne(m, s, viewer);
        item.put("liked", likeService.liked(viewer, momentId));
        return item;
    }

    /* ---------------- 权限判定 ---------------- */

    /**
     * 判断 viewer 是否能看到这条动态。两层权限叠加：
     * 第一层：作者全局查看范围(scope，时间窗口)
     *   - INVISIBLE：除作者外谁都看不到
     *   - THREE_DAY/ONE_MONTH/HALF_YEAR：动态超过对应时间窗口后仅作者可见
     * 第二层：逐条「可见范围」(visibility，关系范围)
     *   - PRIVATE：仅作者（此时 viewer 已非作者，直接不可见）
     *   - PUBLIC：仅好友可见（家园为好友圈，受第一层时间窗口约束）
     *   - PARTIAL：仅 allowList 内的好友可见
     *   - FRIENDS（默认）/未知值：仅好友(isFriend)可见
     * denyList（不给谁看）优先级最高，命中即不可见。
     */
    private boolean visibleTo(Moment m, MomentSetting s, Long viewer, boolean isFriend) {
        if (m.getUserId().equals(viewer)) return true;                       // 作者始终可见
        String scope = s.getVisibility();
        if (scope == null) scope = "HALF_YEAR";
        if ("INVISIBLE".equals(scope)) return false;                         // 家园谁也不可见
        // 时间窗口判断（超过窗口仅作者可见）
        if (m.getCreateTime() != null) {
            LocalDateTime threshold;
            if ("THREE_DAY".equals(scope)) threshold = m.getCreateTime().plusDays(3);
            else if ("ONE_MONTH".equals(scope)) threshold = m.getCreateTime().plusMonths(1);
            else threshold = m.getCreateTime().plusMonths(6);                // HALF_YEAR 及旧值默认半年
            if (LocalDateTime.now().isAfter(threshold)) return false;
        }
        // 逐条：不给谁看（优先级最高）
        if (inList(m.getDenyList(), viewer)) return false;
        // 逐条可见范围（关系范围）
        String vis = m.getVisibility();
        if ("PRIVATE".equals(vis)) return false;                             // 私密：仅作者
        if ("PUBLIC".equals(vis)) return isFriend;                           // 公开：仅好友可见（家园为好友圈，非好友不可见）
        if ("PARTIAL".equals(vis)) {                                         // 指定：仅白名单内好友
            return parseIds(m.getAllowList()).contains(viewer);
        }
        return isFriend;                                                     // FRIENDS（默认）或未知值：仅好友可见
    }

    private boolean inList(String json, Long uid) {
        if (json == null || json.isEmpty()) return false;
        try {
            List<?> list = JSON.readValue(json, List.class);
            for (Object o : list) {
                if (o instanceof Number && ((Number) o).longValue() == uid) return true;
                if (o instanceof String && Long.parseLong(String.valueOf(o)) == uid) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private String toJson(List<Long> ids) {
        if (ids == null) return "[]";
        try {
            return JSON.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Long> parseIds(String json) {
        List<Long> res = new ArrayList<>();
        if (json == null || json.isEmpty()) return res;
        try {
            List<?> list = JSON.readValue(json, List.class);
            for (Object o : list) {
                if (o instanceof Number) res.add(((Number) o).longValue());
                else if (o != null) res.add(Long.parseLong(String.valueOf(o)));
            }
        } catch (Exception ignored) { }
        return res;
    }

    private String normalizeScope(String v) {
        return (v != null && SCOPE.contains(v.toUpperCase())) ? v.toUpperCase() : "HALF_YEAR";
    }

    /** 规范化逐条可见范围：仅接受 PUBLIC/FRIENDS/PRIVATE/PARTIAL，否则回退默认(仅好友) */
    private String normalizeVisibility(String v) {
        return (v != null && REL.contains(v.toUpperCase())) ? v.toUpperCase() : "FRIENDS";
    }

    /* ---------------- 内部工具 ---------------- */

    /** 富化单条动态，附带全局查看范围 + 逐条权限信息。
     *  viewerId 为当前查看者；非作者时不返回 allowList/denyList(具体用户ID列表) 与 scope(全局时间窗口)，
     *  防止他人窥探作者的权限设置。 */
    private Map<String, Object> enrichOne(Moment m, MomentSetting s, Long viewerId) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", m.getId());
        map.put("content", m.getContent());
        map.put("status", m.getStatus() == null ? "NORMAL" : m.getStatus());
        map.put("rejectReason", m.getRejectReason());
        boolean isAuthor = viewerId != null && viewerId.equals(m.getUserId());
        // 全局查看范围（时间窗口）—— 仅作者本人可见，他人无须知晓
        map.put("scope", isAuthor ? s.getVisibility() : null);
        // AI 审核结论与建议——仅作者本人可见（AI 不通过原因需展示给用户本人）
        map.put("aiReview", isAuthor ? m.getAiReview() : null);
        map.put("aiSuggestion", isAuthor ? m.getAiSuggestion() : null);
        map.put("manualReview", isAuthor ? (m.getManualReview() != null && m.getManualReview()) : null);
        // 逐条可见范围（关系范围）
        map.put("visibility", m.getVisibility());
        // 逐条查看权限（allowList/denyList 为具体用户 ID 列表，属隐私信息）—— 仅作者本人可见
        map.put("allowList", isAuthor ? parseIds(m.getAllowList()) : new ArrayList<>());
        map.put("denyList", isAuthor ? parseIds(m.getDenyList()) : new ArrayList<>());
        map.put("createTime", m.getCreateTime() != null ? m.getCreateTime().format(FMT) : null);
        // 解析图片数组
        List<String> imgs = new ArrayList<>();
        try {
            if (m.getImages() != null && !m.getImages().isEmpty()) {
                imgs = JSON.readValue(m.getImages(), List.class);
            }
        } catch (Exception ignored) { }
        map.put("images", imgs);
        // 评论数（feed 子查询已计算，避免额外查询）
        map.put("commentCount", m.getCommentCount() == null ? 0 : m.getCommentCount());
        // 点赞数（feed 子查询已计算）
        map.put("likeCount", m.getLikeCount() == null ? 0 : m.getLikeCount());
        // 作者信息
        User u = userMapper.selectById(m.getUserId());
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
