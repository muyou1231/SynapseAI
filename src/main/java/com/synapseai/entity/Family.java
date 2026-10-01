package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 家族：家族树的容器。
 * <p>
 * 可见性 {@code visibility}：
 * <ul>
 *   <li>0 私有 —— 仅创建人可见</li>
 *   <li>1 链接只读 —— 持有 share_token 的人可查看，不可编辑</li>
 *   <li>2 公开 —— 所有登录用户可见（只读）</li>
 * </ul>
 */
@TableName("family")
public class Family {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 家族名称 */
    private String name;

    /** 家族简介 */
    private String intro;

    /** 家族封面图 URL */
    private String coverUrl;

    /** 创建人 user_id */
    private Long userId;

    /** 可见性：0=私有 1=链接只读 2=公开 */
    private Integer visibility = 0;

    /** 只读分享 token（null=未生成） */
    private String shareToken;

    /** 乐观锁版本号 */
    private Integer version = 0;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0=正常 1=已删除 */
    private Integer deleted = 0;

    // ---------- 非持久化字段：列表页展示用 ----------

    /** 成员数量 */
    private Integer memberCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getVisibility() {
        return visibility;
    }

    public void setVisibility(Integer visibility) {
        this.visibility = visibility;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer memberCount) {
        this.memberCount = memberCount;
    }
}
