package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 朋友圈点赞记录。
 * 唯一键 (moment_id, user_id) 防止同一用户对同一条动态重复点赞；
 * 取消点赞 = 删除对应记录。
 */
@TableName("moment_like")
public class MomentLike {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long momentId;
    private Long userId;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMomentId() { return momentId; }
    public void setMomentId(Long momentId) { this.momentId = momentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
