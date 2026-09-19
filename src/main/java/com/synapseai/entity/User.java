package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 注册时生成的唯一 10 位账号（类似微信号/QQ号），用于搜索添加好友 */
    private String account;

    private String password;

    private String nickname;

    private String avatar;

    /** 性别：男 / 女 / 保密 */
    private String gender;

    /** 年龄（可选，也可由生日推算） */
    private Integer age;

    /** 生日，格式 yyyy-MM-dd（为后续朋友圈功能铺垫） */
    private String birthday;

    /** 宗教信仰（可选） */
    private String religion;

    /** 学历：初中 / 高中 / 大专 / 本科 / 硕士 / 博士 / 其他 */
    private String education;

    /** QQ 式个性签名（一句话简介） */
    private String signature;

    /** 所在地，如：北京 / 上海 / 广东深圳 */
    private String location;

    /** 爱好，多个用逗号或空格分隔 */
    private String hobbies;

    /** 绑定邮箱，用于邮箱验证码登录（可选，未绑定时为 null） */
    private String email;

    /** 在线状态：true=在线，false=离线（退出登录后为离线） */
    private Boolean online = false;

    /** 账号冻结状态：false=正常, true=已冻结（冻结后禁止登录与收发消息） */
    private Boolean frozen = false;

    /** 角色：USER=普通用户, ADMIN=管理员, AI=AI 助手（由管理员在管理端统一维护，不可被普通用户注册/搜索/加好友/改资料） */
    private String role = "USER";

    private LocalDateTime createTime = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getBirthday() {
        return birthday;
    }

    public void setBirthday(String birthday) {
        this.birthday = birthday;
    }

    public String getReligion() {
        return religion;
    }

    public void setReligion(String religion) {
        this.religion = religion;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getHobbies() {
        return hobbies;
    }

    public void setHobbies(String hobbies) {
        this.hobbies = hobbies;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getFrozen() {
        return frozen;
    }

    public void setFrozen(Boolean frozen) {
        this.frozen = frozen;
    }
}
