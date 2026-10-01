package com.synapseai.dto;

import java.util.List;

/**
 * AI 一键建谱：应用解析结果的结构化请求。
 * <p>
 * 先由 {@code /ai/parse} 得到预览（可人工修改），再由 {@code /ai/apply} 提交落库。
 * 成员用临时 ref 互相引用，后端落库时映射成真实 id。
 */
public class AiApplyReq {

    private List<AiMember> members;

    private List<AiUnion> unions;

    public List<AiMember> getMembers() {
        return members;
    }

    public void setMembers(List<AiMember> members) {
        this.members = members;
    }

    public List<AiUnion> getUnions() {
        return unions;
    }

    public void setUnions(List<AiUnion> unions) {
        this.unions = unions;
    }

    /** 待创建的成员（带临时 ref） */
    public static class AiMember {

        /** 临时标识，如 "A"、"p1"，仅需在本请求内唯一 */
        private String ref;

        private String name;

        /** 1=男 2=女 0=未知 */
        private Integer gender;

        /** yyyy-MM-dd */
        private String birthDate;

        private Boolean deceased;

        /** yyyy-MM-dd，可为空（表示逝世日期未知） */
        private String deathDate;

        /**
         * 命中的已有成员 id。有值表示复用该成员而非新建（前端预览时由姓名匹配得出，可人工改）。
         */
        private Long matchId;

        public String getRef() {
            return ref;
        }

        public void setRef(String ref) {
            this.ref = ref;
        }

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

        public Long getMatchId() {
            return matchId;
        }

        public void setMatchId(Long matchId) {
            this.matchId = matchId;
        }
    }

    /** 一段关系（配偶双方）+ 他们的孩子 */
    public static class AiUnion {

        /** 关系双方的 ref，1~2 人；单亲时只放 1 人 */
        private List<String> spouses;

        /** 孩子的 ref */
        private List<String> children;

        public List<String> getSpouses() {
            return spouses;
        }

        public void setSpouses(List<String> spouses) {
            this.spouses = spouses;
        }

        public List<String> getChildren() {
            return children;
        }

        public void setChildren(List<String> children) {
            this.children = children;
        }
    }
}
