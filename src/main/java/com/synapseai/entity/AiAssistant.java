package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * moyo AI 助手配置（每行为一个 AI 助手账户）。
 * 每个 AI 助手对应一个 role=AI 的 user 账户（user_id 关联），聊天/文案能力由本配置驱动；
 * 管理员只能在管理端创建/修改，普通用户不可注册/搜索/加好友/改资料。
 */
@TableName("ai_assistant")
public class AiAssistant {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 关联的 user 表 AI 账户 id（role=AI） */
    private Long userId;

    /** 助手展示名 */
    private String name;

    /** 创建时使用的底层账户密码（仅接收前端传入，不持久化到 ai_assistant 表） */
    @TableField(exist = false)
    private String password;

    /** 底层 user(AI) 账户账号（仅接收前端传入，不持久化到 ai_assistant 表；落库到 user.account） */
    @TableField(exist = false)
    private String account;

    /** 助手头像 */
    private String avatar;

    /** 1=启用(用户可对话) 0=禁用(暂停服务) */
    private Boolean enabled;

    /** ONLINE=在线 / BUSY=忙碌 / OFFLINE=离线 / MAINTENANCE=维护中 */
    private String status;

    /** 是否为默认助手：新用户注册时自动添加；删除默认助手后自动切换到下一个（id 最小者） */
    private Boolean isDefault;

    /** 系统角色设定（助手人设与能力说明） */
    private String prompt;

    /** 助手个性签名 */
    private String signature;

    private LocalDateTime createTime = LocalDateTime.now();

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
