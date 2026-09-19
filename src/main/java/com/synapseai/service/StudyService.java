package com.synapseai.service;

import com.synapseai.entity.StudyPlan;
import com.synapseai.entity.StudyStat;
import com.synapseai.mapper.StudyPlanMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 学习空间：我的学习计划持久化（薄服务层，直接编排 mapper） */
@Service
public class StudyService {

    @Autowired
    private StudyPlanMapper studyPlanMapper;

    /** 保存一份学习计划（归属当前用户，返回主键 id） */
    public long save(Long userId, String title, String content) {
        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setContent(content);
        plan.setCreateTime(LocalDateTime.now());
        studyPlanMapper.insert(plan);
        return plan.getId() == null ? 0L : plan.getId();
    }

    /** 我的计划列表（最新在前） */
    public List<StudyPlan> listByUser(Long userId) {
        return studyPlanMapper.selectByUserId(userId);
    }

    /** 按 id 取单条（校验归属，防越权；非本人返回 null） */
    public StudyPlan getById(Long userId, Long id) {
        StudyPlan plan = studyPlanMapper.selectById(id);
        if (plan == null) return null;
        if (!plan.getUserId().equals(userId)) return null;
        return plan;
    }

    /** 删除（校验归属，非本人忽略） */
    public boolean delete(Long userId, Long id) {
        StudyPlan plan = studyPlanMapper.selectById(id);
        if (plan == null || !plan.getUserId().equals(userId)) return false;
        studyPlanMapper.deleteById(id);
        return true;
    }

    /**
     * 整体替换计划正文（用于前端计划表的勾选/编辑/增删步骤后整份落库）。
     * 仅校验归属与 JSON 合法性，不强制 schema；传入的 content 应为合法 JSON 字符串
     * （结构化计划）或普通文本（兼容旧版纯文本计划）。
     *
     * @return 更新成功返回 true；计划不存在/非本人/JSON 非法返回 false
     */
    public boolean replaceContent(Long userId, Long id, String content) {
        StudyPlan plan = studyPlanMapper.selectById(id);
        if (plan == null || !plan.getUserId().equals(userId)) return false;
        if (content == null) content = "";
        // 合法性校验：若看起来像 JSON（以 { 或 [ 开头），则尝试解析，避免写入损坏数据
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            } catch (Exception e) {
                return false; // JSON 非法，拒绝写入
            }
        }
        if (content.length() > 60000) return false; // 防御超长
        studyPlanMapper.updateContentById(id, content);
        return true;
    }

    /**
     * 计划打卡：累加打卡时长（分钟）与打卡日期（去重），并同步写入当日统计。
     * @param minutes 本次打卡时长（分钟，>=0）；0 表示仅标记「今日已打卡」不计时长
     * @return 成功返回 true；计划不存在/非本人返回 false
     */
    public boolean checkin(Long userId, Long id, int minutes) {
        StudyPlan plan = studyPlanMapper.selectById(id);
        if (plan == null || !plan.getUserId().equals(userId)) return false;
        if (minutes < 0) minutes = 0;
        LocalDate today = LocalDate.now();
        // 打卡日期去重：若今天已在列表里，仅累加时长；否则追加今天
        String days = plan.getCheckinDays() == null ? "" : plan.getCheckinDays();
        String todayStr = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        boolean alreadyToday = !days.isEmpty() && ("," + days + ",").contains("," + todayStr + ",");
        String newDays = days;
        if (!alreadyToday) {
            newDays = days.isEmpty() ? todayStr : (days + "," + todayStr);
        }
        studyPlanMapper.updateStats(id, minutes, 0, newDays, today);
        // 同步当日统计（checkin_count：仅在首次打卡时 +1）
        studyPlanMapper.upsertStat(userId, today, 0, minutes, alreadyToday ? 0 : 1);
        return true;
    }

    /**
     * 番茄钟完成上报：累加到「全局今日统计」（番茄钟是全局专注计时，不绑定具体计划）。
     * @param minutes 完成的番茄钟时长（分钟，>=1）
     * @return 成功返回 true
     */
    public boolean reportPomodoro(Long userId, int minutes) {
        if (minutes <= 0) return false;
        LocalDate today = LocalDate.now();
        studyPlanMapper.upsertStat(userId, today, minutes, 0, 0);
        return true;
    }

    /** 今日统计（番茄钟/打卡时长），无则返回全 0 */
    public StudyStat todayStat(Long userId) {
        return studyPlanMapper.selectToday(userId, LocalDate.now());
    }

    /** 累计统计：{ pomodoro, checkin, cnt }（分钟 / 分钟 / 次数） */
    public java.util.Map<String, Object> sumStat(Long userId) {
        return studyPlanMapper.sumStats(userId);
    }
}
