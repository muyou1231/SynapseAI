package com.moyo.springchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;

    /** TEXT / IMAGE */
    private String type;

    private String content;

    /** USER / GROUP */
    private String targetType;

    private Long targetId;

    private LocalDateTime createTime = LocalDateTime.now();

    /** 接收方是否已读（单聊有效）。read 为 MySQL 保留字，用反引号显示指定列名 */
    @TableField("`read`")
    private Boolean read = false;

    /** 是否已撤回 */
    private Boolean recalled = false;

    /** 是否加急消息 */
    private Boolean urgent = false;

    /** 软删除标记：0=正常, 1=已删除（用户主动删除某条消息） */
    @TableField("deleted")
    private Boolean deleted = false;

    /** 消息炸弹：发送时设定的倒计时秒数，null=非炸弹消息 */
    private Integer bombSeconds;

    /** 消息炸弹：对方须在此时间前回复，否则自动引爆 */
    private LocalDateTime bombDeadline;

    /** 消息炸弹状态：PENDING（倒计时中）/ REPLIED（对方已回复拆弹）/ EXPLODED（已引爆） */
    private String bombStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Boolean getRead() {
        return read;
    }

    public void setRead(Boolean read) {
        this.read = read;
    }

    public Boolean getRecalled() {
        return recalled;
    }

    public void setRecalled(Boolean recalled) {
        this.recalled = recalled;
    }

    public Boolean getUrgent() {
        return urgent;
    }

    public void setUrgent(Boolean urgent) {
        this.urgent = urgent;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public Integer getBombSeconds() {
        return bombSeconds;
    }

    public void setBombSeconds(Integer bombSeconds) {
        this.bombSeconds = bombSeconds;
    }

    public LocalDateTime getBombDeadline() {
        return bombDeadline;
    }

    public void setBombDeadline(LocalDateTime bombDeadline) {
        this.bombDeadline = bombDeadline;
    }

    public String getBombStatus() {
        return bombStatus;
    }

    public void setBombStatus(String bombStatus) {
        this.bombStatus = bombStatus;
    }
}
