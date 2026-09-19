package com.synapseai.dto;

import java.util.List;

/**
 * 管理员发布通知的请求体。
 * targetType = ALL（全员）或 SPECIFIED（指定人）；指定人时 targetIds 为目标用户 id 列表。
 * durationMinutes 为有效时长（分钟），null 或 0 表示永久有效。
 * readWaitSeconds 为阅读停留时长（秒），用户必须等待超过该时长才能点击「我知道了」；null 或 0 表示不限制。
 */
public class NoticePublishReq {

    private String title;
    private String content;
    private String targetType;
    private List<Long> targetIds;
    private Integer durationMinutes;
    private Integer readWaitSeconds;
    /** 关联业务 id（如朋友圈动态 id），用于点击通知跳转到对应内容 */
    private Long refId;

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

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public List<Long> getTargetIds() {
        return targetIds;
    }

    public void setTargetIds(List<Long> targetIds) {
        this.targetIds = targetIds;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getReadWaitSeconds() {
        return readWaitSeconds;
    }

    public void setReadWaitSeconds(Integer readWaitSeconds) {
        this.readWaitSeconds = readWaitSeconds;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }
}
