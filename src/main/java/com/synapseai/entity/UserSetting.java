package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 用户通用开关型设置（一人一行），目前承载③隐身阅读，后续新功能的开关可继续在此扩展列 */
@TableName("user_setting")
public class UserSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 隐身阅读：true=开启后自己阅读消息不会触发已读回执推送给对方 */
    private Boolean ghostRead = false;

    private LocalDateTime updateTime;

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

    public Boolean getGhostRead() {
        return ghostRead;
    }

    public void setGhostRead(Boolean ghostRead) {
        this.ghostRead = ghostRead;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
