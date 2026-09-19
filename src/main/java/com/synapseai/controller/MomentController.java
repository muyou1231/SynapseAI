package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.MomentPublishReq;
import com.synapseai.service.MomentCommentService;
import com.synapseai.service.MomentLikeService;
import com.synapseai.service.MomentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 朋友圈（动态）接口。
 * - POST   /api/moment/publish        发布（需登录）
 * - GET    /api/moment/feed           信息流（本人+好友可见）
 * - GET    /api/moment/mine           我的动态
 * - DELETE /api/moment/{id}           删除自己的动态
 * - GET    /api/moment/setting        查看我的全局权限设置
 * - POST   /api/moment/setting        保存全局权限设置
 * - POST   /api/moment/{id}/comment   评论（文字 + 图片）
 * - GET    /api/moment/{id}/comments  某条动态的评论列表
 * - DELETE /api/moment/comment/{id}    删除评论（作者或动态作者）
 */
@RestController
@RequestMapping("/api/moment")
public class MomentController {

    @Autowired
    private MomentService momentService;
    @Autowired
    private MomentCommentService commentService;
    @Autowired
    private MomentLikeService momentLikeService;

    @PostMapping("/publish")
    public Result<?> publish(@RequestAttribute("uid") Long uid, @RequestBody MomentPublishReq req) {
        try {
            com.synapseai.entity.Moment m = momentService.publish(
                    uid, req.getContent(), req.getImages(), req.getVisibility(), req.getAllowList(), req.getDenyList());
            return Result.ok(momentService.getEnriched(m.getId()));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 申请人工复审：AI 审核不通过后，用户可主动申请由管理员人工复核 */
    @PostMapping("/{id}/manual-review")
    public Result<?> applyManualReview(@RequestAttribute("uid") Long uid, @PathVariable("id") Long momentId) {
        try {
            momentService.applyManualReview(uid, momentId);
            return Result.ok("已申请人工复审，管理员将尽快复核");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/feed")
    public Result<?> feed(@RequestAttribute("uid") Long uid,
                          @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> list = momentService.feed(uid, limit);
            return Result.ok(list);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 单条动态详情（供通知/分享跳转）：带可见性校验，无权查看或非本人动态返回错误 */
    @GetMapping("/{id}")
    public Result<?> detail(@RequestAttribute("uid") Long uid,
                            @PathVariable("id") Long id) {
        try {
            return Result.ok(momentService.getViewable(uid, id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/mine")
    public Result<?> mine(@RequestAttribute("uid") Long uid,
                          @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> list = momentService.myMoments(uid, limit);
            return Result.ok(list);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 查看某用户（好友）的家园动态：按浏览者视角过滤可见范围（本人可见全部，好友可见其公开/好友可见范围） */
    @GetMapping("/user/{userId}")
    public Result<?> userMoments(@RequestAttribute("uid") Long uid,
                                 @PathVariable("userId") Long userId,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> list = momentService.userMoments(uid, userId, limit);
            return Result.ok(list);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        try {
            momentService.delete(uid, id);
            return Result.ok();
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 重新发布（被打回后修改再发）：作者本人，更新内容/图片/可见范围并重置审核状态；返回富化后的动态 */
    @PutMapping("/{id}")
    public Result<?> update(@RequestAttribute("uid") Long uid,
                            @PathVariable("id") Long id,
                            @RequestBody MomentPublishReq req) {
        try {
            com.synapseai.entity.Moment m = momentService.update(
                    uid, id, req.getContent(), req.getImages(), req.getVisibility(), req.getAllowList(), req.getDenyList());
            return Result.ok(momentService.getEnriched(m.getId()));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 修改单条动态的可见范围（仅作者本人）：visibility + allowList + denyList */
    @PutMapping("/{id}/permission")
    public Result<?> updatePermission(@RequestAttribute("uid") Long uid,
                                      @PathVariable("id") Long id,
                                      @RequestBody Map<String, Object> body) {
        try {
            String visibility = body.get("visibility") != null ? String.valueOf(body.get("visibility")) : "FRIENDS";
            List<Long> allowList = parseIds(body.get("allowList"));
            List<Long> denyList = parseIds(body.get("denyList"));
            com.synapseai.entity.Moment m = momentService.updatePermission(uid, id, visibility, allowList, denyList);
            return Result.ok(momentService.getEnriched(m.getId()));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 查看我的全局朋友圈权限设置 */
    @GetMapping("/setting")
    public Result<?> getSetting(@RequestAttribute("uid") Long uid) {
        try {
            return Result.ok(momentService.getSettingView(uid));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 保存全局朋友圈查看范围（scope）：INVISIBLE / THREE_DAY / ONE_MONTH / HALF_YEAR */
    @PostMapping("/setting")
    public Result<?> saveSetting(@RequestAttribute("uid") Long uid,
                                 @RequestBody Map<String, Object> body) {
        try {
            String visibility = body.get("visibility") != null ? String.valueOf(body.get("visibility")) : null;
            return Result.ok(momentService.saveSetting(uid, visibility));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    private List<Long> parseIds(Object v) {
        List<Long> res = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Number) res.add(((Number) o).longValue());
                else if (o != null) res.add(Long.parseLong(String.valueOf(o)));
            }
        }
        return res;
    }

    @PostMapping("/{id}/comment")
    public Result<?> comment(@RequestAttribute("uid") Long uid,
                             @PathVariable("id") Long momentId,
                             @RequestBody Map<String, Object> body) {
        try {
            String content = body.get("content") != null ? String.valueOf(body.get("content")) : null;
            List<String> images = body.get("images") instanceof List
                    ? (List<String>) body.get("images") : null;
            Long parentId = body.get("parentId") != null ? Long.valueOf(String.valueOf(body.get("parentId"))) : null;
            return Result.ok(commentService.comment(uid, momentId, content, images, parentId));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}/comments")
    public Result<?> comments(@RequestAttribute("uid") Long uid,
                              @PathVariable("id") Long momentId) {
        try {
            return Result.ok(commentService.listByMoment(momentId));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/comment/{id}")
    public Result<?> deleteComment(@RequestAttribute("uid") Long uid,
                                   @PathVariable("id") Long commentId) {
        try {
            commentService.delete(uid, commentId);
            return Result.ok();
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 点赞 / 取消点赞（切换）：返回 { liked, likeCount } */
    @PostMapping("/{id}/like")
    public Result<?> like(@RequestAttribute("uid") Long uid,
                          @PathVariable("id") Long id) {
        try {
            return Result.ok(momentLikeService.toggle(uid, id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
