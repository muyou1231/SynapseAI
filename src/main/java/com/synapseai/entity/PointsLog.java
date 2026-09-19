package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 聊天挖矿：积分变动流水（正=获得，负=消耗） */
@TableName("points_log")
public class PointsLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer changeVal;

    /** CHAT_DURATION / MESSAGE_SEND / HIDE_EDIT_MARK 等 */
    private String reason;

    private Long refId;

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

    public Integer getChangeVal() {
        return changeVal;
    }

    public void setChangeVal(Integer changeVal) {
        this.changeVal = changeVal;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
}
