package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 家族成员：族谱中的一个节点。
 * <p>
 * 一个成员可以绑定系统用户（{@code user_id}），也可以只是"被记录的人"（如已故长辈、未注册账号的亲属），
 * 因此 {@code user_id} 允许为空。
 * <p>
 * {@code death_date == null} 表示在世，前端据此渲染不同的节点边框色。
 */
@TableName("family_member")
public class FamilyMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属家族 id */
    private Long familyId;

    /** 绑定的系统用户 id（可空） */
    private Long userId;

    /** 姓名 */
    private String name;

    /** 头像 URL */
    private String avatarUrl;

    /** 性别：0=未知 1=男 2=女 */
    private Integer gender = 0;

    /** 出生日期 */
    private LocalDate birthDate;

    /** 逝世日期（空=在世） */
    private LocalDate deathDate;

    /** 人物简介（生平介绍） */
    private String bio;

    /** 职业 */
    private String occupation;

    /** 籍贯 */
    private String hometown;

    /** 联系电话 */
    private String phone;

    /** 住址 */
    private String address;

    /** 备注 */
    private String remark;

    /** 乐观锁版本号 */
    private Integer version = 0;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0=正常 1=已删除 */
    private Integer deleted = 0;

    // ---------- 非持久化字段：仅用于接口出参 ----------

    /** 该成员在家族树中的辈分（层号，0 起），由树构建阶段回填 */
    private Integer generation;

    /** 关联亲属数量，用于删除前提示影响范围 */
    private Integer relationCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFamilyId() {
        return familyId;
    }

    public void setFamilyId(Long familyId) {
        this.familyId = familyId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public LocalDate getDeathDate() {
        return deathDate;
    }

    public void setDeathDate(LocalDate deathDate) {
        this.deathDate = deathDate;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getOccupation() {
        return occupation;
    }

    public void setOccupation(String occupation) {
        this.occupation = occupation;
    }

    public String getHometown() {
        return hometown;
    }

    public void setHometown(String hometown) {
        this.hometown = hometown;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    public Integer getGeneration() {
        return generation;
    }

    public void setGeneration(Integer generation) {
        this.generation = generation;
    }

    public Integer getRelationCount() {
        return relationCount;
    }

    public void setRelationCount(Integer relationCount) {
        this.relationCount = relationCount;
    }
}
