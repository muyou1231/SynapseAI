package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 朋友圈（动态）实体。
 * visibility: PUBLIC=公开 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见
 * images: JSON 数组字符串，存走 8080 代理的图片 URL
 */
@TableName("moment")
public class Moment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String content;
    /** JSON 数组字符串，如 ["/api/files/...","/api/files/..."] */
    private String images;
    /** PUBLIC / FRIENDS / PRIVATE / PARTIAL(部分好友可见) */
    private String visibility;
    /** NORMAL=正常可见 / PENDING=待审核（人工审核模式下发布或重新发布） / REJECTED=已打回整改（下架，仅作者可见并收到整改通知） */
    private String status;
    /** 打回原因：status=REJECTED 时由管理员填写 */
    private String rejectReason;
    /** AI 审核结论：PASS=通过 / FAIL=不通过；AI 审核模式下写入 */
    private String aiReview;
    /** AI 审核建议/不通过原因（展示给用户与管理员） */
    private String aiSuggestion;
    /** 是否已申请人工复审：0=未申请 1=已申请（转管理员复核） */
    private Boolean manualReview;
    /** JSON 数组字符串，如 [12,34]；PARTIAL 模式下仅这些好友可见 */
    private String allowList;
    /** JSON 数组字符串；这些好友不可见（不给谁看），优先级高于可见范围 */
    private String denyList;
    /** 可见截止时间；超过该时间后仅作者自己可见（访问时间/限时可见） */
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    /** 评论数（由 feed 子查询填充，数据库无此列） */
    @TableField(exist = false)
    private Integer commentCount;
    /** 点赞数（由 feed 子查询填充，数据库无此列） */
    @TableField(exist = false)
    private Integer likeCount;
    /** 当前用户是否点赞（仅在带 viewer 的富化场景下填充） */
    @TableField(exist = false)
    private Boolean liked;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getAiReview() { return aiReview; }
    public void setAiReview(String aiReview) { this.aiReview = aiReview; }
    public String getAiSuggestion() { return aiSuggestion; }
    public void setAiSuggestion(String aiSuggestion) { this.aiSuggestion = aiSuggestion; }
    public Boolean getManualReview() { return manualReview; }
    public void setManualReview(Boolean manualReview) { this.manualReview = manualReview; }
    public String getAllowList() { return allowList; }
    public void setAllowList(String allowList) { this.allowList = allowList; }
    public String getDenyList() { return denyList; }
    public void setDenyList(String denyList) { this.denyList = denyList; }
    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getCommentCount() { return commentCount; }
    public void setCommentCount(Integer commentCount) { this.commentCount = commentCount; }
    public Integer getLikeCount() { return likeCount; }
    public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
    public Boolean getLiked() { return liked; }
    public void setLiked(Boolean liked) { this.liked = liked; }
}
