package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 学习空间：每日学习统计（番茄钟/打卡按天汇总） */
@TableName("study_stats")
public class StudyStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 统计日期 yyyy-MM-dd */
    private LocalDate statDate;

    /** 当日番茄钟总时长（分钟） */
    private Integer pomodoroMinutes = 0;

    /** 当日打卡总时长（分钟） */
    private Integer checkinMinutes = 0;

    /** 当日打卡次数 */
    private Integer checkinCount = 0;

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

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Integer getPomodoroMinutes() {
        return pomodoroMinutes;
    }

    public void setPomodoroMinutes(Integer pomodoroMinutes) {
        this.pomodoroMinutes = pomodoroMinutes;
    }

    public Integer getCheckinMinutes() {
        return checkinMinutes;
    }

    public void setCheckinMinutes(Integer checkinMinutes) {
        this.checkinMinutes = checkinMinutes;
    }

    public Integer getCheckinCount() {
        return checkinCount;
    }

    public void setCheckinCount(Integer checkinCount) {
        this.checkinCount = checkinCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
