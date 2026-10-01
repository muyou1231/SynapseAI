package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 家族大事记：祭祖、迁徙、升学、婚嫁等家族重要事件，按时间倒序展示为时间轴 */
@TableName("family_event")
public class FamilyEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long familyId;

    /** 事件标题 */
    private String title;

    /** 事件日期 */
    private LocalDate eventDate;

    /** 事件内容 */
    private String content;

    /**
     * 关联成员 id，数据库以逗号分隔字符串存储，如 "12,34,56"。
     * 出参时同时提供 {@link #memberIdList} 方便前端多选回填。
     */
    private String memberIds;

    private LocalDateTime createTime;

    // ---------- 非持久化字段 ----------

    /** {@link #memberIds} 的结构化形式（出参） */
    private List<Long> memberIdList = new ArrayList<>();

    /** 关联成员姓名列表（出参，用于时间轴展示） */
    private List<String> memberNames = new ArrayList<>();

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(String memberIds) {
        this.memberIds = memberIds;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public List<Long> getMemberIdList() {
        return memberIdList;
    }

    public void setMemberIdList(List<Long> memberIdList) {
        this.memberIdList = memberIdList;
    }

    public List<String> getMemberNames() {
        return memberNames;
    }

    public void setMemberNames(List<String> memberNames) {
        this.memberNames = memberNames;
    }
}
