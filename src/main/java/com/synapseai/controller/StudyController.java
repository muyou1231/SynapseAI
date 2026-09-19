package com.synapseai.controller;

import com.synapseai.common.Result;
import com.synapseai.dto.StudyPlanReq;
import com.synapseai.entity.StudyPlan;
import com.synapseai.entity.StudyStat;
import com.synapseai.service.StudyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习空间「我的计划」接口（需登录，走统一 X-Token 鉴权）。
 * 仅处理当前用户自己的计划：保存 / 列表 / 查看 / 删除。
 * AI 生成能力由 /api/ai/* 提供（计划 / 互问 / 摘要 / 对话），前端直接调用。
 */
@RestController
@RequestMapping("/api/study")
public class StudyController {

    @Autowired
    private StudyService studyService;

    /** 保存一份学习计划：body { title, content } */
    @PostMapping("/plan")
    public Result<?> savePlan(@RequestAttribute("uid") Long uid, @RequestBody StudyPlanReq req) {
        String title = (req.getTitle() == null) ? null : req.getTitle().trim();
        String content = (req.getContent() == null) ? "" : req.getContent();
        if (title == null || title.isEmpty()) {
            return Result.error("计划标题不能为空");
        }
        if (title.length() > 128) {
            return Result.error("计划标题过长（最多 128 字）");
        }
        long id = studyService.save(uid, title, content);
        return Result.ok(id);
    }

    /** 我的计划列表（最新在前） */
    @GetMapping("/plans")
    public Result<?> myPlans(@RequestAttribute("uid") Long uid) {
        List<StudyPlan> list = studyService.listByUser(uid);
        return Result.ok(list);
    }

    /** 查看单条计划（仅本人） */
    @GetMapping("/plan/{id}")
    public Result<?> getPlan(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        StudyPlan plan = studyService.getById(uid, id);
        if (plan == null) return Result.error("计划不存在");
        return Result.ok(plan);
    }

    /** 删除计划（仅本人） */
    @DeleteMapping("/plan/{id}")
    public Result<?> deletePlan(@RequestAttribute("uid") Long uid, @PathVariable("id") Long id) {
        boolean ok = studyService.delete(uid, id);
        if (!ok) return Result.error("计划不存在或无权限");
        return Result.ok("已删除");
    }

    /**
     * 整体更新计划正文（用于计划表的勾选/编辑/增删步骤后整份保存）。
     * body { content }：应为合法 JSON（结构化计划）或普通文本（兼容旧版）。
     */
    @PutMapping("/plan/{id}")
    public Result<?> updatePlanContent(@RequestAttribute("uid") Long uid,
                                       @PathVariable("id") Long id,
                                       @RequestBody StudyPlanReq req) {
        String content = (req == null || req.getContent() == null) ? "" : req.getContent();
        boolean ok = studyService.replaceContent(uid, id, content);
        if (!ok) {
            // 区分「不存在/无权限」与「JSON 非法」
            StudyPlan existing = studyService.getById(uid, id);
            if (existing == null) return Result.error("计划不存在或无权限");
            return Result.error("计划内容格式非法（需为合法 JSON）");
        }
        return Result.ok("已更新");
    }

    /**
     * 计划打卡：累加打卡时长（分钟）与打卡日期。
     * body { minutes }：本次打卡时长（分钟，>=0；0=仅标记今日已打卡）
     */
    @PostMapping("/plan/{id}/checkin")
    public Result<?> checkin(@RequestAttribute("uid") Long uid,
                             @PathVariable("id") Long id,
                             @RequestBody StudyPlanReq req) {
        int minutes = 0;
        try {
            if (req != null && req.getMinutes() != null) minutes = req.getMinutes();
        } catch (Exception e) { /* 容错 */ }
        if (minutes < 0) minutes = 0;
        if (minutes > 1440) minutes = 1440;
        boolean ok = studyService.checkin(uid, id, minutes);
        if (!ok) return Result.error("计划不存在或无权限");
        return Result.ok("已打卡");
    }

    /**
     * 番茄钟完成上报：累加到全局「今日统计」（番茄钟为全局专注计时，不绑定具体计划）。
     * body { minutes }：完成的番茄钟时长（分钟，>=1）
     */
    @PostMapping("/pomodoro/report")
    public Result<?> reportPomodoro(@RequestAttribute("uid") Long uid,
                                    @RequestBody StudyPlanReq req) {
        int minutes = 0;
        try {
            if (req != null && req.getMinutes() != null) minutes = req.getMinutes();
        } catch (Exception e) { /* 容错 */ }
        if (minutes <= 0) return Result.error("时长无效");
        if (minutes > 1440) minutes = 1440;
        studyService.reportPomodoro(uid, minutes);
        return Result.ok("已记录");
    }

    /** 学习统计总览：今日 + 累计（番茄钟/打卡时长与打卡次数） */
    @GetMapping("/stats")
    public Result<?> stats(@RequestAttribute("uid") Long uid) {
        StudyStat today = studyService.todayStat(uid);
        Map<String, Object> sum = studyService.sumStat(uid);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("todayPomodoro", today == null ? 0 : (today.getPomodoroMinutes() == null ? 0 : today.getPomodoroMinutes()));
        data.put("todayCheckin", today == null ? 0 : (today.getCheckinMinutes() == null ? 0 : today.getCheckinMinutes()));
        data.put("todayCheckinCount", today == null ? 0 : (today.getCheckinCount() == null ? 0 : today.getCheckinCount()));
        data.put("totalPomodoro", sum.get("pomodoro") == null ? 0 : sum.get("pomodoro"));
        data.put("totalCheckin", sum.get("checkin") == null ? 0 : sum.get("checkin"));
        data.put("totalCheckinCount", sum.get("cnt") == null ? 0 : sum.get("cnt"));
        return Result.ok(data);
    }
}
