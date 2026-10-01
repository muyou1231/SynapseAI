package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 亲属关系。
 * <p>
 * <b>语义约定（全局唯一，务必遵守）</b>：一行表示「member_a 是 member_b 的 relation_type」。
 * <ul>
 *   <li>(张三, 李四, FATHER) —— 张三是李四的父亲</li>
 *   <li>(李四, 张三, SON)    —— 李四是张三的儿子（添加子女时自动写入的反向边）</li>
 *   <li>夫妻为双向：(A,B,SPOUSE) 与 (B,A,SPOUSE) 成对存在</li>
 * </ul>
 * 梳理树时只取"长辈→晚辈"方向的边（FATHER/MOTHER/STEP_FATHER/STEP_MOTHER），
 * 反向边（SON/DAUGHTER/ADOPTED_*）用于成员详情的亲属列表展示。
 */
@TableName("family_relation")
public class FamilyRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;

    /** 关系主体：a 是 b 的 xxx */
    private Long memberAId;

    /** 关系客体 */
    private Long memberBId;

    /**
     * 关系类型：
     * SPOUSE / EX_SPOUSE / FATHER / MOTHER / SON / DAUGHTER /
     * STEP_FATHER / STEP_MOTHER / ADOPTED_SON / ADOPTED_DAUGHTER
     */
    private String relationType;

    /** 关系描述，如「1988 年结婚」「自幼过继」 */
    private String relationDesc;

    private LocalDateTime createTime;

    // ---------- 非持久化字段 ----------

    /** 对方成员姓名（列表展示用） */
    private String otherName;

    /** 对方成员头像（列表展示用） */
    private String otherAvatar;

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

    public Long getMemberAId() {
        return memberAId;
    }

    public void setMemberAId(Long memberAId) {
        this.memberAId = memberAId;
    }

    public Long getMemberBId() {
        return memberBId;
    }

    public void setMemberBId(Long memberBId) {
        this.memberBId = memberBId;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getOtherName() {
        return otherName;
    }

    public void setOtherName(String otherName) {
        this.otherName = otherName;
    }

    public String getOtherAvatar() {
        return otherAvatar;
    }

    public void setOtherAvatar(String otherAvatar) {
        this.otherAvatar = otherAvatar;
    }
}
