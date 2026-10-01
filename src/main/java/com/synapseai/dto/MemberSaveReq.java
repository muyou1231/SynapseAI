package com.synapseai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 成员新增 / 编辑请求体。
 * <p>
 * 日期统一用 {@code yyyy-MM-dd} 字符串传输（避免前端时区问题），由 Service 转成 LocalDate。
 * 逝世日期不早于出生日期的校验在 Service 中做（跨字段校验放在业务层，错误信息更友好）。
 */
public class MemberSaveReq {

    /** 编辑时必填；新增时为空 */
    private Long id;

    /** 所属家族 id（新增时必填） */
    private Long familyId;

    @NotBlank(message = "成员姓名不能为空")
    @Size(max = 64, message = "姓名不能超过 64 个字符")
    private String name;

    /** 绑定的系统用户 id（可空） */
    private Long userId;

    @Size(max = 512, message = "头像地址过长")
    private String avatarUrl;

    /** 性别：0=未知 1=男 2=女 */
    private Integer gender = 0;

    /**
     * 出生日期 yyyy-MM-dd。允许留空 —— 未填写时视为「未知」（前端显示「未知」）。
     * 具体格式校验由 FamilyService.parseDate 负责，此处需放行空串，否则留空无法保存。
     */
    @Pattern(regexp = "^(|\\d{4}-\\d{2}-\\d{2})$", message = "出生日期格式应为 yyyy-MM-dd")
    private String birthDate;

    /**
     * 是否已逝世。true 表示已逝世；此时 deathDate 为空表示「逝世日期未知」。
     * 不传时由 deathDate 是否有值推导，保证旧客户端兼容。
     */
    private Boolean deceased;

    /** 逝世日期 yyyy-MM-dd（deceased=true 时可为空，表示日期未知） */
    @Pattern(regexp = "^(|\\d{4}-\\d{2}-\\d{2})$", message = "逝世日期格式应为 yyyy-MM-dd")
    private String deathDate;

    @Size(max = 4000, message = "人物简介不能超过 4000 个字符")
    private String bio;

    @Size(max = 128, message = "职业不能超过 128 个字符")
    private String occupation;

    @Size(max = 128, message = "籍贯不能超过 128 个字符")
    private String hometown;

    @Size(max = 32, message = "联系电话不能超过 32 个字符")
    private String phone;

    @Size(max = 255, message = "住址不能超过 255 个字符")
    private String address;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    /** 乐观锁版本号（编辑时必填） */
    private Integer version;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    public Boolean getDeceased() {
        return deceased;
    }

    public void setDeceased(Boolean deceased) {
        this.deceased = deceased;
    }

    public String getDeathDate() {
        return deathDate;
    }

    public void setDeathDate(String deathDate) {
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
}
