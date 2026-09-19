package com.synapseai.dto;

import java.util.List;

/**
 * 发布朋友圈请求体
 * 注意：可见权限已改为「全局家园设置」，不再逐条指定。
 */
public class MomentPublishReq {

    /** 文案（可为空，但 images 与 content 不能同时为空，由 Service 校验） */
    private String content;
    /** 图片代理 URL 列表（已通过 /api/file/upload 上传到 MinIO） */
    private List<String> images;
    /** 逐条「可见范围」：PUBLIC=公开 / FRIENDS=仅好友可见(默认) / PRIVATE=仅自己 / PARTIAL=部分好友可见 */
    private String visibility = "FRIENDS";
    /** 逐条「部分可见」：仅这些好友可见本条动态（PARTIAL 模式生效） */
    private List<Long> allowList;
    /** 逐条「不给谁看」：这些好友不可见本条动态（优先级最高） */
    private List<Long> denyList;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public List<Long> getAllowList() { return allowList; }
    public void setAllowList(List<Long> allowList) { this.allowList = allowList; }
    public List<Long> getDenyList() { return denyList; }
    public void setDenyList(List<Long> denyList) { this.denyList = denyList; }
}
