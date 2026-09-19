package com.synapseai.dto;

import java.util.Map;

/** 更新个人信息请求体（所有字段均可选，前端只传需要修改的字段） */
public class ProfileUpdateDto {

    private String nickname;
    private String avatar;
    private String gender;
    private Integer age;
    private String birthday;
    private String religion;
    private String education;

    /** QQ 式个性签名 */
    private String signature;
    /** 所在地 */
    private String location;
    /** 爱好 */
    private String hobbies;

    /** 绑定邮箱（用于邮箱验证码登录）；传 null/空串表示不变，传空串由后端按"清空"语义处理 */
    private String email;

    /** 字段可见范围设置：field -> visibility(PUBLIC/FRIENDS/PRIVATE)，仅本人可设置 */
    private Map<String, String> privacy;

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

    public Map<String, String> getPrivacy() {
        return privacy;
    }

    public void setPrivacy(Map<String, String> privacy) {
        this.privacy = privacy;
    }
}
