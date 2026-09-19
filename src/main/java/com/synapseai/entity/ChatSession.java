package com.synapseai.entity;

import java.time.LocalDateTime;

/**
 * 用户自建会话（聊天的会话管理）。
 * 不属于好友也不属于群，是用户自己的「私人会话/笔记会话」，仅自己可见。
 * 消息复用 message 表，target_type='SESSION'、target_id=session.id、sender=uid（自己发给自己）。
 */
public class ChatSession {

    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
