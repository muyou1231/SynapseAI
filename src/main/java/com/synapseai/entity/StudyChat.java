package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 学习空间：AI 对话历史（后端持久化，刷新/换设备可靠恢复，流式实时落库中途不丢） */
@TableName("study_chat")
public class StudyChat {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** user=用户消息 / ai=AI 回复 */
    private String role;

    /** 对话模式：chat/plan/quiz/summarize（保留以便未来分模式展示） */
    private String mode;

    /** 子线：同一会话下 chat/plan/quiz/summarize 四条独立记录线之一（各自独立 seq 与记忆） */
    private String thread = "chat";

    /** 所属 AI 会话 id（关联 study_ai_session）；0 表示改造前的旧数据 */
    private Long sessionId = 0L;

    /** 消息内容（流式过程中实时更新 AI 回复） */
    private String content;

    /** 同一用户会话内的顺序号，从 1 递增，用于回放排序 */
    private Integer seq;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = (thread == null || thread.isEmpty()) ? "chat" : thread;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
