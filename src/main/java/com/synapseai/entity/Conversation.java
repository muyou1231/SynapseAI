package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 消息列表（会话列表）持久化实体。
 * 记录「某个用户的某个会话」是否被删除（隐藏），使会话列表可独立于消息/好友关系独立管理。
 * 唯一键 (user_id, target_type, target_id)：同一用户对同一会话只有一行。
 */
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String targetType;   // USER / GROUP
    private Long targetId;
    /** 1=用户已删除该会话（消息列表不显示）；0=正常 */
    private Boolean deleted = false;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
