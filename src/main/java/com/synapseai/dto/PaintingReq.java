package com.synapseai.dto;

/** 创建绘画作品请求体 */
public class PaintingReq {

    /** 作品标题（必填） */
    private String title;

    /** 作品描述 */
    private String description;

    /** 图片地址（MinIO 代理 URL 或外部链接，必填） */
    private String imageUrl;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
