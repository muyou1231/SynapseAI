package com.synapseai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 亲属关系维护请求体（统一入口：添加配偶 / 子女 / 父母 / 继父母 / 养子女 / 标记离异）。
 * <p>
 * {@code relationType} 取值为业务动作：
 * <ul>
 *   <li>SPOUSE —— 添加配偶（双向写入两条 SPOUSE）</li>
 *   <li>EX_SPOUSE —— 标记离异（把已有的双向 SPOUSE 改成 EX_SPOUSE）</li>
 *   <li>CHILD —— 添加子女（自动写入 FATHER + MOTHER 两条，及其反向边）</li>
 *   <li>ADOPTED_CHILD —— 添加养子女（写入 ADOPTED_SON / ADOPTED_DAUGHTER）</li>
 *   <li>PARENT —— 添加父母（自动写入 FATHER / MOTHER）</li>
 *   <li>STEP_PARENT —— 添加继父母（写入 STEP_FATHER / STEP_MOTHER）</li>
 * </ul>
 * 对方可以是已有成员（传 {@code relativeId}），也可以现场新建（传 {@code newRelative}）。
 */
public class RelationCreateReq {

    /** 关系主体成员 id（"我"） */
    @NotNull(message = "成员 id 不能为空")
    private Long memberId;

    /** 对方成员 id；为空表示现场新建一位成员 */
    private Long relativeId;

    @NotNull(message = "关系类型不能为空")
    private String relationType;

    /** 关系描述，如「1988 年结婚」「自幼过继」 */
    @Size(max = 255, message = "关系描述不能超过 255 个字符")
    private String relationDesc;

    /** relativeId 为空时，用它新建成员 */
    private NewRelative newRelative;

    /** 现场新建成员时的简要信息 */
    public static class NewRelative {

        @Size(max = 64, message = "姓名不能超过 64 个字符")
        private String name;

        /** 性别：0=未知 1=男 2=女 */
        private Integer gender = 0;

        /** yyyy-MM-dd */
        private String birthDate;

        /** yyyy-MM-dd，空=在世 */
        private String deathDate;

        private String avatarUrl;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public String getDeathDate() {
            return deathDate;
        }

        public void setDeathDate(String deathDate) {
            this.deathDate = deathDate;
        }

        public String getAvatarUrl() {
            return avatarUrl;
        }

        public void setAvatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
        }
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Long getRelativeId() {
        return relativeId;
    }

    public void setRelativeId(Long relativeId) {
        this.relativeId = relativeId;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getRelationDesc() {
        return relationDesc;
    }

    public void setRelationDesc(String relationDesc) {
        this.relationDesc = relationDesc;
    }

    public NewRelative getNewRelative() {
        return newRelative;
    }

    public void setNewRelative(NewRelative newRelative) {
        this.newRelative = newRelative;
    }
}
