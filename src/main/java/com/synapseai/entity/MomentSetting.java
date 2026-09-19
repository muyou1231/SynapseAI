package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 朋友圈全局权限设置（用户级，一人一条）。
 * visibility: PUBLIC=公开 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见 / PARTIAL=部分好友可见
 * allowList:  JSON 数组，PARTIAL 模式下仅这些好友可见
 * denyList:   JSON 数组，不给谁看（优先级最高）
 * expireTime: 可见截止时间；超过后仅作者可见（对所有动态统一生效）
 */
@TableName("moment_setting")
public class MomentSetting {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** PUBLIC / FRIENDS / PRIVATE / PARTIAL */
    private String visibility;
    /** JSON 数组字符串，如 [12,34] */
    private String allowList;
    /** JSON 数组字符串 */
    private String denyList;
    /** 可见截止时间 */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getAllowList() { return allowList; }
    public void setAllowList(String allowList) { this.allowList = allowList; }
    public String getDenyList() { return denyList; }
    public void setDenyList(String denyList) { this.denyList = denyList; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
