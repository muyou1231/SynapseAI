package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 管理员下发的通知。
 * targetType = ALL 表示全员可见；= SPECIFIED 表示仅 targetIds（逗号分隔的用户 id）可见。
 * durationMinutes 为有效时长（分钟），null 表示永久有效；expireAt 由后端在发布时计算（createTime + duration）。
 */
@TableName("notice")
public class Notice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通知标题 */
    private String title;

    /** 通知正文 */
    private String content;

    /** 发布者（管理员）id */
    private Long senderId;

    /** ALL=全员 / SPECIFIED=指定人 */
    private String targetType;

    /** 指定人时的目标用户 id 列表，逗号分隔（如 "3,7,12"） */
    private String targetIds;

    /** 有效时长（分钟），null=永久 */
    private Integer durationMinutes;

    /** 过期时间（发布时间 + durationMinutes），null=永久 */
    private LocalDateTime expireAt;

    /** 通知类别：ADMIN=管理员下发通知 / COMMENT=朋友圈评论与回复通知（用于「多多的家园」红点区分） */
    private String category;

    /** 阅读时长（秒）：用户必须等待超过该时长才能点击「我知道了」；0 / NULL = 不限制 */
    private Integer readWaitSeconds;

    /** 关联业务 id：COMMENT 类通知关联对应的 moment（动态）id；ADMIN 等类通知可为空 */
    private Long refId;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetIds() {
        return targetIds;
    }

    public void setTargetIds(String targetIds) {
        this.targetIds = targetIds;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /** 传输用标记：当前用户是否已读（数据库无此列） */
    @TableField(exist = false)
    private Boolean read;

    public Boolean getRead() {
        return read;
    }

    public void setRead(Boolean read) {
        this.read = read;
    }
}
