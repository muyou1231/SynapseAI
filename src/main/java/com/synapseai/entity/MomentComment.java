package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 朋友圈（动态）评论。
 * images: JSON 数组字符串，可空（纯文字评论时为空数组），存走 8080 代理的图片 URL
 */
@TableName("moment_comment")
public class MomentComment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long momentId;
    private Long userId;
    /** 父评论 id：回复某条评论时填写，顶级评论为 null */
    private Long parentId;
    private String content;
    /** JSON 数组字符串，如 ["/api/files/...","/api/files/..."]，无图时为空数组 */
    private String images;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMomentId() { return momentId; }
    public void setMomentId(Long momentId) { this.momentId = momentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
