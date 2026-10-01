package com.synapseai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 家族大事记新增 / 编辑请求体 */
public class EventSaveReq {

    /** 编辑时必填 */
    private Long id;

    /** 新增时必填 */
    private Long familyId;

    @NotBlank(message = "事件标题不能为空")
    @Size(max = 128, message = "事件标题不能超过 128 个字符")
    private String title;

    @NotBlank(message = "事件日期不能为空")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "事件日期格式应为 yyyy-MM-dd")
    private String eventDate;

    @Size(max = 4000, message = "事件内容不能超过 4000 个字符")
    private String content;

    /** 关联成员 id 列表（可空） */
    private List<Long> memberIds;

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

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<Long> getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(List<Long> memberIds) {
        this.memberIds = memberIds;
    }
}
