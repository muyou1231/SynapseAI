package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 资料字段可见范围设置。
 * visibility 取值：PUBLIC=所有人可见 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见
 */
@TableName("user_privacy")
public class UserPrivacy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 资料字段名，如 signature / location / hobbies / gender / age / birthday / religion / education / createDays */
    private String field;

    /** 可见范围：PUBLIC / FRIENDS / PRIVATE */
    private String visibility;

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

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
