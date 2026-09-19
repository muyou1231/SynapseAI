package com.synapseai.dto;

/** 保存学习计划请求体 */
public class StudyPlanReq {

    /** 计划标题（必填） */
    private String title;

    /** 计划正文（AI 生成或手动编辑的内容） */
    private String content;

    /** 打卡/番茄钟时长（分钟） */
    private Integer minutes;

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

    public Integer getMinutes() {
        return minutes;
    }

    public void setMinutes(Integer minutes) {
        this.minutes = minutes;
    }
}
