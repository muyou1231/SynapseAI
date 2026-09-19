package com.synapseai.entity;

import java.time.LocalDateTime;

/** 学习空间：AI 对话「会话」（按主题/轮次拆分，每个会话一组 study_chat 消息） */
public class StudyAiSession {

    private Long id;
    private Long userId;
    private String title;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
