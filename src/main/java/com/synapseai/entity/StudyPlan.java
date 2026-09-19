package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 学习空间：用户保存的「我的学习计划」（AI 生成或手动编辑后保存） */
@TableName("study_plan")
public class StudyPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 计划标题，如「30 天搞定高数」 */
    private String title;

    /** 计划正文（AI 生成的周计划 / 微任务，纯文本或 Markdown 风格文本） */
    private String content;

    /** 累计打卡时长（分钟） */
    private Integer checkinMinutes = 0;

    /** 累计番茄钟时长（分钟） */
    private Integer pomodoroMinutes = 0;

    /** 打卡日期列表，逗号分隔 yyyy-MM-dd */
    private String checkinDays = "";

    /** 最后打卡日期 */
    private java.time.LocalDate lastCheckin;

    private LocalDateTime createTime = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCheckinMinutes() {
        return checkinMinutes;
    }

    public void setCheckinMinutes(Integer checkinMinutes) {
        this.checkinMinutes = checkinMinutes;
    }

    public Integer getPomodoroMinutes() {
        return pomodoroMinutes;
    }

    public void setPomodoroMinutes(Integer pomodoroMinutes) {
        this.pomodoroMinutes = pomodoroMinutes;
    }

    public String getCheckinDays() {
        return checkinDays;
    }

    public void setCheckinDays(String checkinDays) {
        this.checkinDays = checkinDays;
    }

    public java.time.LocalDate getLastCheckin() {
        return lastCheckin;
    }

    public void setLastCheckin(java.time.LocalDate lastCheckin) {
        this.lastCheckin = lastCheckin;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
